package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.integration.PaymentAdapter;
import com.cloudagent.business.integration.ResourceAdapter;
import com.cloudagent.business.repository.JobRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 数据库持久化任务的执行器，负责通知投递、开通、续费、资源操作与退款等后台工作。 定时轮询只是一种触发方式；任务事实存在 MySQL，不依靠 Java 内存里的任务列表。 claim
 * 先提交领取结果和租约，process 再执行任务；进程中断后可重新领取过期租约。 使用 TransactionTemplate
 * 显式控制短事务，避免同类内部调用时误以为 @Transactional 会另开事务。 任务可能被再次执行，因此还要结合唯一业务号、子项状态和供应商查询实现幂等；
 * 这不是消息“绝对只投递一次”的承诺，也不是完整通用分布式队列。
 */
@org.springframework.stereotype.Service
public class JobService {
  private final JsonCodec json;
  private final BusinessEvents events;

  private final JobRepository repository;
  private final TradeService trade;
  private final MarketingService marketing;
  private final DeliveryService delivery;
  private final PaymentAdapter payments;
  private final ResourceAdapter resources;
  private final TransactionTemplate tx;
  private final boolean enabled;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public JobService(
      JsonCodec json,
      BusinessEvents events,
      JobRepository repository,
      TradeService trade,
      MarketingService marketing,
      DeliveryService delivery,
      PaymentAdapter payments,
      ResourceAdapter resources,
      PlatformTransactionManager tm,
      @Value("${platform.jobs-enabled}") boolean enabled) {
    this.json = json;
    this.events = events;

    this.repository = repository;
    this.trade = trade;
    this.marketing = marketing;
    this.delivery = delivery;
    this.payments = payments;
    this.resources = resources;
    // tm是Spring注入的事务管理器。模板让下面的lambda代码块获得明确的事务范围。
    tx = new TransactionTemplate(tm);
    this.enabled = enabled;
  }

  /** 每轮结束后延迟1秒再触发下一轮；先处理关单、渠道补偿和到期扫描，再各取最多20条事件与任务。不是为每个任务new一个线程。 */
  // fixedDelay按“上次调用结束后再等1000毫秒”计算，不是每秒强行并发启动一轮。
  public void poll() {
    if (!enabled) return;
    try {
      closeExpired();
      reconcilePayments();
      expiry();
      for (int i = 0; i < 20; i++) {
        String id = claim("outbox");
        if (id == null) break;
        process("outbox", id);
      }
      for (int i = 0; i < 20; i++) {
        String id = claim("tasks");
        if (id == null) break;
        process("tasks", id);
      }
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Job cycle failed; durable work retained: {}", e.getClass().getSimpleName());
    }
  }

  /** 事务内锁定一条到执行时间的PENDING或过期RUNNING记录，更新为RUNNING并设置60秒租约，再提交领取结果。 */
  private String claim(String table) {
    // execute会等待lambda执行结束并提交，然后才把lambda的返回值交给调用者；不是提交一个异步线程。
    return tx.execute(
        status -> {
          var rows = repository.lockDueWork(table, now());
          if (rows.isEmpty()) return null;
          String id = text(rows.get(0), "id");
          repository.markWorkRunning(table, time(Instant.now().plusSeconds(60)), id);
          return id;
        });
  }

  /** 用新事务执行已领取任务并标成功；失败时原执行事务回滚，再用另一事务记录次数、退避时间及人工处理状态。 */
  private void process(String table, String id) {
    try {
      // 领取事务已结束，这里开始执行事务；同一事务内保护业务修改和任务成功标记。
      tx.executeWithoutResult(
          status -> {
            var row = repository.lockWork(table, id);
            if (!text(row, "state").equals("RUNNING")) return;
            if (table.equals("outbox")) outbox(row);
            else task(row);
            repository.markWorkSucceeded(table, id);
          });
      // 前一个executeWithoutResult抛错时已回滚；用下一段事务记录失败，避免失败计数也一起被回滚掉。
    } catch (Exception e) {
      tx.executeWithoutResult(
          status -> {
            var row = repository.lockWork(table, id);
            int attempts = (int) num(row, "attempts") + 1;
            String state = attempts >= 3 ? "MANUAL_REQUIRED" : "PENDING";
            repository.recordWorkFailure(
                table,
                state,
                attempts,
                time(Instant.now().plusSeconds(Math.min(300, 1L << attempts))),
                id);
            if (table.equals("tasks")) {
              String error = e instanceof ApiError ? e.getMessage() : "任务执行失败，可使用请求号联系管理员";
              repository.updateTaskError(error, id);
              if (attempts >= 3) failed(row, error);
            }
            events.audit(
                "WORKER",
                "TASK_RETRY",
                id,
                "状态：" + state + "；次数：" + attempts + "；错误类型：" + e.getClass().getSimpleName());
          });
    }
  }

  /** 按事件类型投递：通知用唯一事件号去重，支付创建调用渠道适配器，订单创建事件只保留已发生事实。 */
  private void outbox(Map<String, Object> event) {
    var p = json.parse(event.get("payload"));
    switch (text(event, "type")) {
      case "NOTICE" -> {
        repository.insertNotification(
            id(),
            p.get("user_id"),
            event.get("event_key"),
            p.get("title"),
            p.get("content"),
            now());
        delivery.notice(
            text(event, "event_key"), text(p, "user_id"), text(p, "title"), text(p, "content"));
      }
      case "PAYMENT_CREATE" -> {
        var payment = repository.findPayment(p.get("payment_id"));
        payments.create(text(payment, "id"), num(payment, "amount_cents"));
      }
      case "ORDER_CREATED" -> {} // A durable business event, independent from later
        // payment/notification delivery.
      default -> throw new IllegalStateException("Unknown outbox type");
    }
  }

  /** 按固定type把任务分派到开通、续费、操作、到期或退款处理函数；未知类型不允许静默当成功。 */
  private void task(Map<String, Object> t) {
    switch (text(t, "type")) {
      case "PROVISION" -> provision(t);
      case "RENEW" -> renew(t);
      case "ACTION" -> action(t);
      case "SUSPEND", "EXPIRE_RELEASE" -> expire(t);
      case "REFUND" -> refund(t);
      default -> throw new IllegalStateException("Unknown task type");
    }
  }

  /** 锁订单和子项，先检查是否已经成功或进入退款，再按稳定实例业务号查询/创建供应商资源，最后保存本地实例与成功子项。 */
  private void provision(Map<String, Object> t) {
    var o = repository.lockOrder(t.get("order_id"));
    var p = json.parse(t.get("payload"));
    var item = repository.lockItem(p.get("item_id"));
    if (text(item, "state").equals("SUCCEEDED")) return;
    require(
        !bool(item.get("refunded")) && !bool(item.get("refund_pending")),
        409,
        "REFUND_PENDING",
        "履约子项已进入退款");
    var config = json.parse(o.get("config"));
    var snapshot = json.parse(o.get("snapshot"));
    String instance = text(item, "instance_id");
    var faults = repository.findSimulationFault(instance);
    String fault = faults.isEmpty() ? "" : text(faults.get(0), "mode");
    require(!fault.equals("FAIL"), 503, "SIMULATED_PROVIDER_FAILURE", "模拟供应商故障（管理员配置）");
    // 先记下供应商是否已有结果，故障演示可模拟“第一次创建成功、响应却超时”。
    boolean providerExists = repository.countProviderResources(instance) > 0;
    // resources是Spring注入的适配器代理；其REQUIRES_NEW台账可先提交，外层任务失败也不会抹掉该供应商事实。
    var provider =
        resources.ensureCreated(
            instance,
            Instant.now().atZone(ZoneOffset.UTC).plusMonths(num(o, "months")).toInstant());
    if (!providerExists && fault.equals("TIMEOUT_AFTER_COMMIT"))
      throw new ApiError(504, "PROVIDER_TIMEOUT", "模拟供应商已提交但响应超时，重试时先查询");
    repository.insertInstance(
        instance,
        o.get("user_id"),
        o.get("id"),
        o.get("sku_id"),
        config.get("name"),
        o.get("config"),
        provider.get("provider_id"),
        "192.0.2." + (Math.floorMod(instance.hashCode(), 250) + 1),
        now(),
        provider.get("updated_at"),
        provider.get("expires_at"),
        snapshot.get("grace_days"));
    repository.markItemSucceeded(item.get("id"));
    finishOrder(o);
    events.notice(
        "provision:" + instance,
        text(o, "user_id"),
        "模拟实例开通成功",
        "实例：" + instance + "；模拟地址不能用于真实登录");
    events.audit("WORKER", "SIMULATED_PROVISION", instance, "供应商幂等号：" + instance);
  }

  /** 锁定订单、子项和实例；未到期从原到期时间延长，宽限期内从当前恢复时间起算。查询远端期限以恢复提交成功但本地未提交的结果。 */
  private void renew(Map<String, Object> t) {
    var o = repository.lockOrder(t.get("order_id"));
    String itemId = text(json.parse(t.get("payload")), "item_id");
    var item = repository.lockItem(itemId);
    if (text(item, "state").equals("SUCCEEDED")) return;
    require(
        !bool(item.get("refunded")) && !bool(item.get("refund_pending")),
        409,
        "REFUND_PENDING",
        "续费已进入退款");
    var r = repository.lockInstance(t.get("instance_id"));
    require(
        Set.of("RUNNING", "STOPPED", "SUSPENDED").contains(text(r, "state")),
        409,
        "RESOURCE_STATE_CONFLICT",
        "当前资源不可续费");
    Instant old = instant(r.get("expires_at"));
    require(
        old.plus(Duration.ofDays(num(r, "grace_days"))).isAfter(Instant.now()),
        409,
        "RENEWAL_EXPIRED",
        "已超过续费宽限期");
    Instant start = old.isAfter(Instant.now()) ? old : Instant.now();
    Instant target = start.atZone(ZoneOffset.UTC).plusMonths(num(o, "months")).toInstant();
    // A persisted provider expiry is consulted after a timeout before making another mutation.
    // 远端期限若已延长，说明可能上次调用成功但本地提交失败，应恢复已有结果，不再额外加一次租期。
    var provider = resources.query(text(r, "id"));
    Instant remote = instant(provider.get("expires_at"));
    if (remote.isAfter(old)) target = remote;
    else resources.renew(text(r, "id"), target);
    repository.insertRenewal(o.get("id"), r.get("id"), r.get("expires_at"), time(target), now());
    repository.renewInstance(time(target), r.get("id"));
    repository.markItemSucceeded(itemId);
    finishOrder(o);
    events.notice(
        "renewed:" + o.get("id"),
        text(o, "user_id"),
        "续费成功",
        "实例：" + r.get("id") + "；新到期：" + target);
    events.audit("WORKER", "RENEWED", text(r, "id"), "前：" + old + "；后：" + target);
  }

  /** 根据逐台子项的成功/失败数汇总订单：全部成功、部分成功、全部失败或仍履约中；不是一台失败就覆盖整单。 */
  private void finishOrder(Map<String, Object> order) {
    long success = repository.countSucceededItems(order.get("id")),
        failed = repository.countFailedItems(order.get("id"));
    String state =
        success == num(order, "quantity")
            ? "COMPLETED"
            : success + failed == num(order, "quantity")
                ? (success > 0 ? "PARTIALLY_FULFILLED" : "FULFILLMENT_FAILED")
                : "FULFILLING";
    repository.updateOrderState(state, order.get("id"));
  }

  /** 核对op_version，调用模拟供应商确认目标状态后才更新最终状态；释放时同步归还已分配库存。 */
  private void action(Map<String, Object> t) {
    var r = repository.lockInstance(t.get("instance_id"));
    var p = json.parse(t.get("payload"));
    require(num(r, "op_version") == num(p, "version"), 409, "STALE_OPERATION", "资源操作版本已变化");
    String action = text(p, "action"),
        target =
            action.equals("STOP") ? "STOPPED" : action.equals("RELEASE") ? "RELEASED" : "RUNNING";
    if (!action.equals("RELEASE"))
      require(
          instant(r.get("expires_at")).isAfter(Instant.now()), 409, "INSTANCE_EXPIRED", "实例已到期");
    resources.state(text(r, "id"), target);
    if (target.equals("RELEASED")) releaseLocal(r);
    else repository.updateInstanceState(target, r.get("id"));
    events.audit(
        "WORKER", "RESOURCE_ACTION", text(r, "id"), "前：" + r.get("state") + "；后：" + target);
    events.notice(
        "action-done:" + t.get("id"),
        text(r, "user_id"),
        "实例操作完成",
        "模拟实例：" + r.get("id") + "；状态：" + target);
  }

  /** 只在本地实例尚未RELEASED时释放并扣减allocated，防止重复归还同一份库存；由上层任务事务保护。 */
  private void releaseLocal(Map<String, Object> r) {
    if (!text(r, "state").equals("RELEASED")) {
      repository.markInstanceReleased(r.get("id"));
      require(
          repository.releaseAllocatedStock(r.get("sku_id")) == 1,
          409,
          "INVENTORY_CONFLICT",
          "已分配库存异常");
    }
  }

  /** 再次读取并锁定实例，核对任务记录的旧到期时间与当前到期时间。已续费的旧任务直接结束，避免付款后被旧任务释放。 */
  private void expire(Map<String, Object> t) {
    var r = repository.lockInstance(t.get("instance_id"));
    if (text(r, "state").equals("RELEASED")) return;
    var p = json.parse(t.get("payload"));
    // 到期任务保存的是创建任务时的期限。当前期限已变说明发生过续费，这个旧任务应当失效。
    if (!instant(r.get("expires_at")).toString().equals(text(p, "expires"))) return;
    if (instant(r.get("expires_at")).isAfter(Instant.now())) return;
    // Never race an already paid renewal or approved refund resource operation.
    require(
        repository.countFulfillingRenewals(r.get("id")) == 0, 409, "RENEWAL_PENDING", "等待已付款续费");
    if (text(t, "type").equals("EXPIRE_RELEASE")) {
      require(
          !instant(r.get("expires_at"))
              .plus(Duration.ofDays(num(r, "grace_days")))
              .isAfter(Instant.now()),
          409,
          "GRACE_NOT_EXPIRED",
          "宽限期未结束");
      resources.state(text(r, "id"), "RELEASED");
      releaseLocal(r);
    } else {
      if (!Set.of("RUNNING", "STOPPED", "SUSPENDED").contains(text(r, "state")))
        throw new ApiError(409, "RESOURCE_BUSY", "资源操作中");
      resources.state(text(r, "id"), "SUSPENDED");
      repository.suspendInstance(r.get("id"));
    }
    events.notice(
        "expired:" + t.get("id"),
        text(r, "user_id"),
        text(t, "type").equals("SUSPEND") ? "实例已到期暂停" : "宽限期结束，实例已释放",
        "模拟实例：" + r.get("id"));
  }

  /** 锁定订单/申请/子项；必要时先释放供应商资源并归还库存，再执行幂等渠道退款，最后结清占用额度与已退款金额。 */
  private void refund(Map<String, Object> t) {
    var o = repository.lockOrder(t.get("order_id"));
    String id = text(json.parse(t.get("payload")), "refund_id");
    var f = repository.lockRefund(id);
    if (text(f, "state").equals("SUCCEEDED")) return;
    require(
        Set.of("APPROVED", "RESOURCE_PROCESSING", "REFUNDING", "FAILED").contains(text(f, "state")),
        409,
        "REFUND_STATE_CONFLICT",
        "退款状态不可执行");
    var item = repository.lockItem(f.get("item_id"));
    repository.markRefundProcessing(id);
    if (text(o, "kind").equals("NEW_PURCHASE") && !text(item, "state").equals("CANCELLED")) {
      String instance = text(item, "instance_id");
      var rows = repository.lockOptionalInstance(instance);
      if (!rows.isEmpty()) {
        var r = rows.get(0);
        if (!text(r, "state").equals("RELEASED")) {
          resources.state(instance, "RELEASED");
          releaseLocal(r);
        }
      } else {
        if (repository.countProviderResources(instance) > 0) resources.state(instance, "RELEASED");
        require(
            repository.releaseAllocatedStock(o.get("sku_id")) == 1,
            409,
            "INVENTORY_CONFLICT",
            "失败项库存异常");
      }
    }
    repository.markRefundRefunding(id);
    var pay = repository.findSuccessfulPayment(o.get("id"));
    // 用退款申请ID作为稳定渠道业务号；如果渠道已成功、本地随后回滚，下次仍查询/使用同一笔退款。
    String provider = payments.refund(id, text(pay, "id"), num(f, "amount_cents"));
    repository.completeRefund(provider, id);
    repository.markItemRefunded(item.get("id"));
    repository.applyRefundTotals(f.get("amount_cents"), f.get("amount_cents"), o.get("id"));
    marketing.refunded(repository.lockOrder(o.get("id")), text(f, "id"));
    events.notice(
        "refund-paid:" + id, text(o, "user_id"), "模拟退款成功", "退款金额（分）：" + f.get("amount_cents"));
    events.audit("WORKER", "SIMULATED_REFUND", id, "金额分：" + f.get("amount_cents"));
  }

  /** 达到重试上限后的业务补偿：履约标人工处理、资源操作恢复可追踪状态、退款标失败；不会伪造供应商已经成功。 */
  private void failed(Map<String, Object> t, String error) {
    if (Set.of("PROVISION", "RENEW").contains(text(t, "type"))) {
      var o = repository.lockOrder(t.get("order_id"));
      String item = text(json.parse(t.get("payload")), "item_id");
      repository.markItemManual(item);
      finishOrder(o);
      events.notice(
          "task-failed:" + t.get("id"), text(o, "user_id"), "履约需要人工处理", error + "，可申请失败项退款或提交工单");
    }
    if (text(t, "type").equals("ACTION")) {
      var p = json.parse(t.get("payload"));
      repository.updateInstanceStateAtVersion(
          p.get("old_state"), t.get("instance_id"), p.get("version"));
    }
    if (text(t, "type").equals("REFUND"))
      repository.markRefundFailed(json.parse(t.get("payload")).get("refund_id"));
  }

  /** 批量找待付款超时订单，再在各自事务中锁订单重查状态/期限，避免查询后又收到付款仍被错误关闭。 */
  private void closeExpired() {
    for (var r : repository.findExpiredOrders(now()))
      tx.executeWithoutResult(
          st -> {
            var o = repository.lockOrder(r.get("id"));
            if (text(o, "state").equals("PENDING_PAYMENT")
                && !instant(o.get("payment_deadline")).isAfter(Instant.now()))
              trade.close(o, "CLOSED");
          });
  }

  /** 主动查询模拟渠道台账，补记失败/取消或成功结果；即使成功通知丢失，轮询也能继续推动订单。 */
  private void reconcilePayments() {
    for (var failed : repository.findFailedProviderPayments())
      tx.executeWithoutResult(
          st -> {
            if (repository.closeFailedPayment(
                    text(failed, "outcome").equals("FAILED") ? "FAILED" : "CLOSED",
                    failed.get("id"))
                == 1) {
              repository.insertPaymentEvent(
                  id(),
                  failed.get("id"),
                  text(failed, "transaction_id") + ":" + text(failed, "outcome"),
                  failed.get("amount_cents"),
                  failed.get("outcome"),
                  now());
              events.notice(
                  "payment-failed:" + failed.get("id"),
                  text(failed, "user_id"),
                  "模拟付款未完成",
                  "请在订单支付窗口内重试或取消订单");
              events.audit(
                  "PAYMENT_ADAPTER",
                  "SIMULATED_PAYMENT_FAILED",
                  text(failed, "id"),
                  "结果：" + failed.get("outcome"));
            }
          });
    for (var p : repository.findUnreconciledSuccesses())
      trade.paymentNotification(
          text(p, "id"), text(p, "transaction_id"), num(p, "amount_cents"), "CNY");
  }

  /** 使用主键游标每批扫描1000台近期到期资源，写去重提醒或到期任务；扫描只入队，实际状态改变由expire二次确认。 */
  private void expiry() {
    // 按主键游标翻页，不是每轮只处理最早1000条；这样超过一批的资源也会被扫描。
    String cursor = "";
    while (true) {
      var batch =
          repository.scanExpiringInstances(cursor, time(Instant.now().plus(Duration.ofDays(8))));
      if (batch.isEmpty()) break;
      cursor = text(batch.get(batch.size() - 1), "id");
      for (var r : batch)
        tx.executeWithoutResult(
            st -> {
              Instant expiry = instant(r.get("expires_at"));
              String id = text(r, "id");
              long days = Duration.between(Instant.now(), expiry).toDays();
              for (int threshold : List.of(7, 3, 1))
                if (days >= 0 && days <= threshold)
                  events.notice(
                      "remind:" + id + ":" + expiry + ":" + threshold,
                      text(r, "user_id"),
                      "实例到期提醒",
                      "模拟实例 " + id + " 将在" + expiry + "到期，请及时手动续费");
              if (!expiry.isAfter(Instant.now())) {
                boolean release =
                    !expiry.plus(Duration.ofDays(num(r, "grace_days"))).isAfter(Instant.now());
                trade.task(
                    (release ? "expire-release:" : "suspend:") + id + ":" + expiry,
                    release ? "EXPIRE_RELEASE" : "SUSPEND",
                    null,
                    id,
                    Map.of("expires", expiry.toString()));
              }
            });
      if (batch.size() < 1000) break;
    }
  }

  /** 仅管理员可重置人工处理任务，需记录原因；已经退款/正在退款的履约子项不能重新开通。 */
  @org.springframework.transaction.annotation.Transactional
  public Object retry(AuthService.Actor a, String id, String reason, boolean outbox) {
    a.adminOnly();
    String table = outbox ? "outbox" : "tasks";
    var t = repository.lockWork(table, id);
    require(text(t, "state").equals("MANUAL_REQUIRED"), 409, "TASK_STATE_CONFLICT", "仅人工处理状态可重试");
    if (!outbox && Set.of("PROVISION", "RENEW").contains(text(t, "type"))) {
      var item = repository.lockItem(json.parse(t.get("payload")).get("item_id"));
      require(
          !bool(item.get("refunded")) && !bool(item.get("refund_pending")),
          409,
          "REFUND_PENDING",
          "子项已进入退款，不可再次开通");
      repository.resetItem(item.get("id"));
      repository.markOrderFulfilling(t.get("order_id"));
    }
    repository.resetWork(table, now(), id);
    events.audit(a.id(), "TASK_MANUAL_RETRY", id, "原因：" + reason);
    return repository.findWork(table, id);
  }
}
