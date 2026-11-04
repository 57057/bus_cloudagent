package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.integration.PaymentAdapter;
import com.cloudagent.business.repository.TradeRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交易主流程：报价、创建订单、预留库存、取消/关单、支付确认与任务入队。 Controller 传入 Actor 和请求参数；本服务校验业务规则，并在事务中组合多条 SQL。
 * 这里不直接开通资源，付款确认后写入 p0_tasks，交给 JobRunner 异步履约。 库存含义：capacity 总量，reserved 待支付预留，allocated 已付款占用；
 * 可售库存 = capacity - reserved - allocated。allocated 也可能包含等待开通或失败待处理项。
 */
@Service
public class TradeService {
  private final JsonCodec json;
  private final BusinessEvents events;

  private final TradeRepository repository;
  private final CatalogService catalog;
  private final PaymentAdapter payments;
  private final MarketingService marketing;
  private final int quoteMinutes, paymentMinutes;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public TradeService(
      JsonCodec json,
      BusinessEvents events,
      TradeRepository repository,
      CatalogService catalog,
      PaymentAdapter payments,
      MarketingService marketing,
      @Value("${platform.quote-minutes}") int quoteMinutes,
      @Value("${platform.payment-minutes}") int paymentMinutes) {
    this.json = json;
    this.events = events;

    this.repository = repository;
    this.catalog = catalog;
    this.payments = payments;
    this.marketing = marketing;
    this.quoteMinutes = quoteMinutes;
    this.paymentMinutes = paymentMinutes;
  }

  /** 把记录编号和认证用户编号放进同一条WHERE；不存在和不属于本人都返回404。table只能从内部白名单选择，lock决定是否加FOR UPDATE。 */
  public Map<String, Object> owned(String table, String id, String user, boolean lock) {
    require(
        Set.of("orders", "payments", "instances", "quotes", "refunds", "tickets", "chat_sessions")
            .contains(table),
        500,
        "INTERNAL_ERROR",
        "非法数据表");
    return repository.findOwned(table, lock, id, user);
  }

  /** 新购读取当前SKU，续费读取本人实例原配置；计算租期金额并保存有效期、配置和规则快照。此时不预留库存。 */
  @Transactional
  public Object quote(AuthService.Actor a, Map<String, Object> d, String instance) {
    Map<String, Object> config, sku;
    int quantity;
    String kind = instance == null ? "NEW_PURCHASE" : "RENEWAL";
    if (instance == null) {
      sku = catalog.available(input(d, "sku_id", 64));
      config = new LinkedHashMap<>();
      for (String k : List.of("image", "disk_type", "disk_gb", "bandwidth", "name"))
        config.put(k, d.get(k));
      catalog.validateConfig(sku, config);
      quantity = integer(d, "quantity", 1, 10);
    } else {
      var resource = owned("instances", instance, a.id(), true);
      require(
          Set.of("RUNNING", "STOPPED", "SUSPENDED").contains(text(resource, "state")),
          409,
          "RESOURCE_STATE_CONFLICT",
          "实例当前不可续费");
      require(
          instant(resource.get("expires_at"))
              .plus(Duration.ofDays(num(resource, "grace_days")))
              .isAfter(Instant.now()),
          409,
          "RENEWAL_EXPIRED",
          "已超过续费宽限期");
      sku = catalog.available(text(resource, "sku_id"));
      config = json.parse(resource.get("config"));
      quantity = 1;
    }
    int months = integer(d, "months", 1, 12);
    require(
        Arrays.asList(text(sku, "periods").split(",")).contains("" + months),
        400,
        "INVALID_CONFIG",
        "不支持的租用周期");
    long unit = CatalogService.unitPrice(sku, config, months),
        total = Math.multiplyExact(unit, quantity);
    String id = id();
    // 复制SKU形成当时报价快照：以后运营改价或改规则，不能回头修改这份已保存的约定。
    var snapshot = new LinkedHashMap<>(sku);
    snapshot.put("discount_percent", months == 12 ? 85 : 100);
    snapshot.put("refund_rule", "首次开通后" + sku.get("refund_days") + "天内按剩余租期比例退费；失败项全额退；续费订单仅未履约可退");
    snapshot.put("rule_version", 1);
    snapshot.put("currency", "CNY");
    snapshot.put("original_total_cents", total);
    String grant = Objects.toString(d.get("coupon_grant_id"), "");
    if (!grant.isBlank()) {
      long discount = marketing.discount(a, grant, total);
      snapshot.put("coupon_grant_id", grant);
      snapshot.put("coupon_discount_cents", discount);
      total -= discount;
    }
    String referral = Objects.toString(d.get("promotion_link"), "");
    if (!referral.isBlank()) snapshot.put("promotion_link", referral);
    // 整数分分摊；订单子项将余数分给最后一项，确保退款上限等于实付。
    unit = total / quantity;
    repository.insertQuote(
        id,
        a.id(),
        sku.get("id"),
        kind,
        instance,
        json.json(config),
        json.json(snapshot),
        unit,
        total,
        quantity,
        months,
        time(Instant.now().plusSeconds(quoteMinutes * 60L)));
    return owned("quotes", id, a.id(), false);
  }

  /** 按幂等键检查已有结果，锁用户/报价/可售配置，校验配额并预留库存，最后把订单、逐台子项和事件一起提交。 */
  @Transactional
  public Object createOrder(AuthService.Actor a, Map<String, Object> d, String key) {
    require(
        key != null && key.matches("[A-Za-z0-9_-]{8,100}"),
        400,
        "IDEMPOTENCY_REQUIRED",
        "请提供8~100位幂等键");
    String quoteId = input(d, "quote_id", 64);
    String requestHash = AuthService.hash(quoteId);
    // Serializing per user also makes quota and same-key races deterministic.
    // 同一用户的下单先串行检查配额与幂等键；不同用户仍需要SKU条件更新共同保护库存。
    // 这里是数据库事务锁，不是Java synchronized，也不会锁住整台Java进程。
    repository.lockUser(a.id());
    var existing = repository.findOrderByIdempotencyKey(a.id(), key);
    // 幂等不是“遇到重复就报错”：相同键和相同报价应返回原订单；同键换参数才返回冲突。
    if (!existing.isEmpty()) {
      require(
          text(existing.get(0), "request_hash").equals(requestHash),
          409,
          "IDEMPOTENCY_CONFLICT",
          "同一幂等键不能提交不同报价");
      return detail(a, text(existing.get(0), "id"));
    }
    var q = owned("quotes", quoteId, a.id(), true);
    require(
        instant(q.get("expires_at")).isAfter(Instant.now()), 409, "PRICE_EXPIRED", "报价已过期，请重新报价");
    var sku = catalog.available(text(q, "sku_id"));
    int quantity = (int) num(q, "quantity");
    if (text(q, "kind").equals("NEW_PURCHASE")) {
      long active = repository.countUnreleasedInstances(a.id());
      long pending = repository.sumReservedInstances(a.id());
      // 已付款但还没形成实例的子项也占配额，否则可利用异步开通时间差连续买入绕过上限。
      long awaiting = repository.countUnprovisionedItems(a.id());
      require(
          active + pending + awaiting + quantity <= 20,
          409,
          "QUOTA_EXCEEDED",
          "账号最多持有20台活动、待履约或待支付实例");
      catalog.validateConfig(sku, json.parse(q.get("config")));
      // 把“库存足够”的判断写进UPDATE条件，受影响行数为1才算预留成功。
      // 不能先SELECT可售数量，再无条件UPDATE，否则并发请求可能依据同一个旧余额扣库存。
      require(
          repository.reserveStock(quantity, q.get("sku_id"), quantity) == 1,
          409,
          "INSUFFICIENT_STOCK",
          "可售库存不足");
    } else {
      var resource = owned("instances", text(q, "instance_id"), a.id(), true);
      require(
          Set.of("RUNNING", "STOPPED", "SUSPENDED").contains(text(resource, "state")),
          409,
          "RESOURCE_STATE_CONFLICT",
          "实例不可续费");
      require(
          repository.countPendingRenewals(resource.get("id")) == 0,
          409,
          "RENEWAL_PENDING",
          "已有待处理续费订单");
      require(
          repository.countPendingRefunds(resource.get("id")) == 0,
          409,
          "REFUND_PENDING",
          "退款中的实例不能续费");
    }
    String id = id();
    repository.insertOrder(
        id,
        a.id(),
        quoteId,
        key,
        requestHash,
        q.get("sku_id"),
        q.get("kind"),
        q.get("instance_id"),
        "PENDING_PAYMENT",
        q.get("config"),
        q.get("snapshot"),
        q.get("unit_cents"),
        q.get("total_cents"),
        quantity,
        q.get("months"),
        time(Instant.now().plusSeconds(paymentMinutes * 60L)),
        now());
    var benefitsSnapshot = json.parse(q.get("snapshot"));
    marketing.attach(
        a,
        id,
        benefitsSnapshot,
        ((Number) benefitsSnapshot.getOrDefault("original_total_cents", q.get("total_cents")))
            .longValue(),
        text(sku, "product_id"));
    for (int i = 0; i < quantity; i++)
      repository.insertItem(
          id(),
          id,
          a.id(),
          text(q, "kind").equals("RENEWAL") ? q.get("instance_id") : id(),
          i == quantity - 1
              ? num(q, "total_cents") - num(q, "unit_cents") * (quantity - 1)
              : q.get("unit_cents"),
          "PENDING");
    // 订单、库存、逐台子项与outbox事件处于同一事务；抛运行时异常时一起回滚。
    // HTTP响应成功只代表下单成功，不代表已付款或已开通。
    events.event("order:" + id, "ORDER_CREATED", Map.of("order_id", id));
    events.audit(a.id(), "ORDER_CREATED", id, "金额分：" + q.get("total_cents"));
    return detail(a, id);
  }

  /** 先验证订单归属，再组合子项、支付单、任务与退款记录；不能先返回订单附属数据再补做归属检查。 */
  public Object detail(AuthService.Actor a, String id) {
    var order = new LinkedHashMap<>(owned("orders", id, a.id(), false));
    order.put("items", repository.listOrderItems(id));
    order.put("payments", repository.listOrderPayments(id));
    order.put("tasks", repository.listOrderTasks(id));
    order.put("refunds", repository.listOrderRefunds(id));
    return order;
  }

  /** 只允许用户取消本人未付款订单；重复取消已取消订单直接返回原结果，其他状态返回冲突。 */
  @Transactional
  public Object cancel(AuthService.Actor a, String id) {
    var o = owned("orders", id, a.id(), true);
    if (text(o, "state").equals("CANCELLED")) return o;
    require(text(o, "state").equals("PENDING_PAYMENT"), 409, "ORDER_STATE_CONFLICT", "只有未支付订单可取消");
    close(o, "CANCELLED");
    events.audit(a.id(), "ORDER_CANCELLED", id, "用户取消");
    return owned("orders", id, a.id(), false);
  }

  /** 调用者持有订单事务/锁时使用：按预期状态关单，释放新购预留，关闭尚未完成的支付请求。不会取消已确认的成功付款。 */
  public void close(Map<String, Object> o, String state) {
    if (repository.closePendingOrder(state, o.get("id")) == 0) return;
    if (text(o, "kind").equals("NEW_PURCHASE"))
      require(
          repository.releaseReservedStock(o.get("quantity"), o.get("sku_id"), o.get("quantity"))
              == 1,
          409,
          "INVENTORY_CONFLICT",
          "库存预留异常");
    repository.cancelItems(o.get("id"));
    repository.closePayments(o.get("id"));
    marketing.release(o.get("id"));
  }

  /** 校验本人订单可付款和支付期限；同一订单复用支付单，通过outbox异步创建模拟渠道请求。 */
  @Transactional
  public Object newPayment(AuthService.Actor a, String order) {
    var o = owned("orders", order, a.id(), true);
    require(
        text(o, "state").equals("PENDING_PAYMENT")
            && instant(o.get("payment_deadline")).isAfter(Instant.now()),
        409,
        "ORDER_STATE_CONFLICT",
        "订单不可付款或已超时");
    var existing = repository.listOrderPayments(order);
    if (!existing.isEmpty()) return existing.get(0);
    String id = id();
    repository.insertPayment(id, order, a.id(), o.get("total_cents"), "SIMULATED", now());
    events.event("payment-create:" + id, "PAYMENT_CREATE", Map.of("payment_id", id));
    return owned("payments", id, a.id(), false);
  }

  /** 处理服务端已核验的成功付款：锁订单/支付单、核对金额和去重。正常付款转履约并创建逐台任务；晚到付款保留事实但不开通。 */
  @Transactional
  public Object paymentNotification(String payment, String provider, long amount, String currency) {
    var p = repository.findPayment(payment);
    // 付款、用户取消和超时关单都围绕同一订单行锁协商，不能仅在前端禁止按钮点击。
    var o = repository.lockOrder(p.get("order_id"));
    p = repository.lockPayment(payment);
    require(
        currency.equals("CNY") && amount == num(p, "amount_cents"),
        400,
        "PAYMENT_AMOUNT_MISMATCH",
        "支付币种或金额不符");
    var prior = repository.findProviderEvents(provider);
    if (!prior.isEmpty()) {
      require(
          text(prior.get(0), "payment_id").equals(payment)
              && num(prior.get(0), "amount_cents") == amount,
          409,
          "PAYMENT_EVENT_CONFLICT",
          "支付流水不匹配");
      return p;
    }
    require(!text(p, "state").equals("SUCCEEDED"), 409, "PAYMENT_EVENT_CONFLICT", "同一支付单出现不同成功流水");
    // 渠道确实收款，与订单当前还能不能履约，是两个独立事实；关闭后收到款也要保存流水。
    boolean eligible =
        text(o, "state").equals("PENDING_PAYMENT")
            && instant(o.get("payment_deadline")).isAfter(Instant.now());
    if (text(o, "state").equals("PENDING_PAYMENT") && !eligible) close(o, "CLOSED");
    repository.insertPaymentEvent(
        id(), payment, provider, amount, eligible ? "ACCEPTED" : "LATE_PAYMENT", now());
    repository.markPaymentSucceeded(provider, payment);
    marketing.paid(o, eligible);
    if (eligible) {
      // 成功付款记录与任务入队在同一事务内提交，因此界面直接看到FULFILLING；
      // 中间没有单独提交一个PAID阶段，支付事实保存在支付单与支付事件里。
      repository.markOrderFulfilling(o.get("id"));
      if (text(o, "kind").equals("NEW_PURCHASE"))
        require(
            repository.allocateReservedStock(
                    o.get("quantity"), o.get("quantity"), o.get("sku_id"), o.get("quantity"))
                == 1,
            409,
            "INVENTORY_CONFLICT",
            "库存预留异常");
      for (var item : repository.listOrderItems(o.get("id")))
        task(
            "fulfill:" + item.get("id"),
            text(o, "kind").equals("RENEWAL") ? "RENEW" : "PROVISION",
            text(o, "id"),
            text(item, "instance_id"),
            Map.of("item_id", item.get("id")));
    } else {
      events.notice(
          "late:" + payment,
          text(o, "user_id"),
          "异常付款待处理",
          "已关闭订单收到模拟付款，请申请退款或联系工单。订单：" + o.get("id"));
      events.audit("PAYMENT_ADAPTER", "LATE_PAYMENT", payment, "进入异常付款队列，不开通资源");
    }
    events.notice("paid:" + payment, text(o, "user_id"), "模拟付款成功", "订单：" + o.get("id"));
    events.audit(
        "PAYMENT_ADAPTER",
        "SIMULATED_PAYMENT",
        payment,
        "金额分：" + amount + "；结果：" + (eligible ? "ACCEPTED" : "LATE_PAYMENT"));
    return owned("payments", payment, text(o, "user_id"), false);
  }

  /** 以稳定business_key插入待执行任务，重复调用不新增同一业务任务；由调用方事务决定何时可被后台看见。 */
  public void task(
      String key, String type, String order, String instance, Map<String, Object> payload) {
    repository.insertTask(id(), key, type, order, instance, json.json(payload), now(), now());
  }

  /** 校验当前用户后模拟渠道结果，仅补偿任务可确认商户付款。 */
  public Object simulate(String id, Map<String, Object> d, AuthService.Actor a) {
    require(
        Boolean.TRUE.equals(d.get("confirmed")),
        400,
        "CONFIRMATION_REQUIRED",
        "请确认使用模拟支付，不会产生真实扣款");
    var p = owned("payments", id, a.id(), false);
    String outcome = Objects.toString(d.get("outcome"), "SUCCEEDED");
    require(
        Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(outcome),
        400,
        "INVALID_INPUT",
        "未知模拟渠道结果");
    payments.settle(
        id,
        num(p, "amount_cents"),
        outcome); /* Only the reconciliation worker consumes provider results. */
    return Map.of("state", "PROCESSING", "channel", "SIMULATED", "message", "模拟渠道已受理，请查询支付结果");
  }
}
