package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.repository.ResourceRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面向用户的资源操作和退款申请服务。 启动/停止/重启/释放只提交任务及中间状态，不在用户 HTTP 请求里假装资源已经操作成功。
 * 退款先算额度并锁定额度，运营审核通过后由后台先处理资源，再调用模拟支付退款。 所有本人接口都通过认证用户和数据库归属查询保护，传入实例编号不等于拥有它。
 */
@Service
public class ResourceService {
  private final JsonCodec json;
  private final BusinessEvents events;

  private final ResourceRepository repository;
  private final TradeService trade;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public ResourceService(
      JsonCodec json, BusinessEvents events, ResourceRepository repository, TradeService trade) {
    this.json = json;
    this.events = events;

    this.repository = repository;
    this.trade = trade;
  }

  /** 验证实例归属后，组合实例、操作任务和续费记录。 */
  public Object detail(AuthService.Actor a, String id) {
    var r = new LinkedHashMap<>(trade.owned("instances", id, a.id(), false));
    r.put("operations", repository.listInstanceTasks(id));
    r.put("renewals", repository.listInstanceRenewals(id));
    return r;
  }

  /** 验证幂等键、资源归属/状态和冲突中的续费退款；释放要求确认，然后先写操作中状态与版本号，再创建任务。 */
  @Transactional
  public Object action(AuthService.Actor a, String id, Map<String, Object> d, String key) {
    var r = trade.owned("instances", id, a.id(), true);
    String action = input(d, "action", 20);
    require(
        Set.of("START", "STOP", "RESTART", "RELEASE").contains(action),
        400,
        "INVALID_ACTION",
        "未知资源操作");
    require(
        key != null && key.matches("[A-Za-z0-9_-]{8,100}"), 400, "IDEMPOTENCY_REQUIRED", "需要幂等键");
    String businessKey = "action:" + id + ":" + key;
    var prior = repository.findTaskByKey(businessKey);
    if (!prior.isEmpty()) {
      require(
          text(json.parse(prior.get(0).get("payload")), "action").equals(action),
          409,
          "IDEMPOTENCY_CONFLICT",
          "同一幂等键的操作不一致");
      return prior.get(0);
    }
    String state = text(r, "state");
    require(!state.equals("RELEASED"), 409, "RESOURCE_RELEASED", "资源已释放");
    require(repository.countPendingRenewals(id) == 0, 409, "RENEWAL_PENDING", "续费期间暂不可操作资源");
    require(repository.countPendingInstanceRefunds(id) == 0, 409, "REFUND_PENDING", "退款期间暂不可操作资源");
    require(
        action.equals("RELEASE")
            ? Set.of("RUNNING", "STOPPED", "SUSPENDED").contains(state)
            : action.equals("START") ? state.equals("STOPPED") : state.equals("RUNNING"),
        409,
        "RESOURCE_STATE_CONFLICT",
        "资源当前状态不支持此操作");
    if (action.equals("RELEASE"))
      require(
          Boolean.TRUE.equals(d.get("confirmed")),
          400,
          "CONFIRMATION_REQUIRED",
          "释放需二次确认，资源及数据将不可恢复");
    if (!action.equals("RELEASE"))
      require(
          instant(r.get("expires_at")).isAfter(Instant.now()),
          409,
          "INSTANCE_EXPIRED",
          "资源已到期，请续费");
    // 每次操作递增版本，后台任务只能处理它提交时对应的那一代资源状态，防止旧操作覆盖新操作。
    int version = (int) num(r, "op_version") + 1;
    repository.updateInstanceOperation(
        action.equals("RELEASE")
            ? "RELEASING"
            : action.equals("STOP") ? "STOPPING" : action + "ING",
        version,
        id);
    trade.task(
        businessKey,
        "ACTION",
        null,
        id,
        Map.of("action", action, "version", version, "old_state", state));
    events.audit(
        a.id(),
        "RESOURCE_" + action,
        id,
        "前：" + state + "；提交异步操作；已确认：" + d.getOrDefault("confirmed", false));
    return repository.findTask(businessKey);
  }

  /** 锁订单并重新计算可退额度，写申请、占用子项与订单退款额度；此时没有真正向渠道退款。 */
  @Transactional
  public Object requestRefund(AuthService.Actor a, Map<String, Object> d) {
    String order = input(d, "order_id", 64),
        itemId = input(d, "item_id", 64),
        reason = input(d, "reason", 500);
    var o = trade.owned("orders", order, a.id(), true);
    long amount = refundable(a, o, order, itemId);
    String id = id();
    repository.insertRefund(id, a.id(), order, itemId, amount, reason, now());
    // 锁定退款额度，不等同于已经退款：并发申请要同时考虑已退金额和处理中占用额。
    repository.markItemRefundPending(itemId);
    repository.holdRefundAmount(amount, order);
    events.audit(a.id(), "REFUND_REQUESTED", id, "金额分：" + amount + "；原因：" + reason);
    return trade.owned("refunds", id, a.id(), false);
  }

  /** 复用同一计算规则给用户展示预计可退金额，不写退款申请或占用额度；实际提交时仍会重新计算。 */
  @Transactional
  public Object previewRefund(AuthService.Actor a, String order, String itemId) {
    var o = trade.owned("orders", order, a.id(), true);
    long amount = refundable(a, o, order, itemId);
    return Map.of(
        "amount_cents",
        amount,
        "currency",
        "CNY",
        "rule",
        json.parse(o.get("snapshot")).get("refund_rule"));
  }

  /** 要求已实付、子项未退款/未占用且履约已结束；成功新购按快照期限和剩余秒数算退款，失败或晚到付款取消项可全退。 */
  private long refundable(AuthService.Actor a, Map<String, Object> o, String order, String itemId) {
    require(repository.countSuccessfulPayments(order) > 0, 409, "NOT_PAID", "订单尚未实付");
    var item = repository.lockOrderItem(itemId, order);
    require(
        !bool(item.get("refunded")) && !bool(item.get("refund_pending")),
        409,
        "REFUND_ALREADY_REQUESTED",
        "该子项已退款或正在退款");
    require(
        !Set.of("FULFILLING", "PAID").contains(text(o, "state")),
        409,
        "FULFILLMENT_PENDING",
        "开通或续费正在处理中，请等待结果");
    long amount = num(item, "amount_cents");
    if (text(item, "state").equals("SUCCEEDED")) {
      require(
          text(o, "kind").equals("NEW_PURCHASE"),
          409,
          "REFUND_NOT_ALLOWED",
          "已履约续费不支持首版自动退订，请提交工单");
      var r = trade.owned("instances", text(item, "instance_id"), a.id(), true);
      require(
          Set.of("RUNNING", "STOPPED", "SUSPENDED").contains(text(r, "state")),
          409,
          "RESOURCE_STATE_CONFLICT",
          "资源当前不可退订");
      require(
          repository.countInstanceRenewals(r.get("id")) == 0,
          409,
          "REFUND_NOT_ALLOWED",
          "已续费资源请提交工单处理退订");
      require(
          repository.countPendingRenewalsForRefund(r.get("id")) == 0,
          409,
          "RENEWAL_PENDING",
          "资源存在待处理续费");
      var rules = json.parse(o.get("snapshot"));
      Instant start = instant(r.get("starts_at")), end = instant(r.get("expires_at"));
      require(
          start.plus(Duration.ofDays(num(rules, "refund_days"))).isAfter(Instant.now()),
          409,
          "REFUND_WINDOW_EXPIRED",
          "超过订单快照规定的退款期限");
      // 成功开通项按“实付子项金额×剩余秒数/总租期秒数”向下取整到分；Math.max避免除0或负剩余时间。
      long duration = Math.max(1, Duration.between(start, end).getSeconds()),
          remaining = Math.max(0, Duration.between(Instant.now(), end).getSeconds());
      amount = Math.min(amount, Math.multiplyExact(amount, remaining) / duration);
    } else
      require(
          Set.of("FAILED", "MANUAL_REQUIRED", "CANCELLED").contains(text(item, "state")),
          409,
          "REFUND_NOT_ALLOWED",
          "该子项暂不可退款");
    require(
        amount > 0
            && num(o, "refunded_cents") + num(o, "refund_held_cents") + amount
                <= num(o, "total_cents"),
        409,
        "REFUND_LIMIT",
        "可退款额度不足");
    return amount;
  }

  /** 运营锁定订单及退款申请后审核。拒绝释放占用额度；通过创建后台退款任务，而不是直接把申请标成资金已退。 */
  @Transactional
  public Object review(AuthService.Actor a, String id, Map<String, Object> d) {
    a.staffOnly();
    String reason = input(d, "reason", 500);
    var first = repository.findRefund(id);
    repository.lockOrder(first.get("order_id"));
    var refund = repository.lockRefund(id);
    require(text(refund, "state").equals("SUBMITTED"), 409, "REFUND_STATE_CONFLICT", "退款已处理");
    boolean approve = Boolean.TRUE.equals(d.get("approve"));
    repository.reviewRefund(approve ? "APPROVED" : "REJECTED", reason, id);
    if (approve)
      trade.task("refund:" + id, "REFUND", text(refund, "order_id"), null, Map.of("refund_id", id));
    else {
      repository.releaseRefundHold(refund.get("amount_cents"), refund.get("order_id"));
      repository.clearItemRefundPending(refund.get("item_id"));
    }
    events.notice(
        "refund-review:" + id, text(refund, "user_id"), approve ? "退款审核通过" : "退款申请被拒绝", reason);
    events.audit(
        a.id(),
        "REFUND_REVIEW",
        id,
        "前：SUBMITTED；后：" + (approve ? "APPROVED" : "REJECTED") + "；原因：" + reason);
    return repository.findRefund(id);
  }

  /** 模拟资源无真实监控；先校验归属，再返回明确的空样本。 */
  public Object metrics(String id, AuthService.Actor a) {
    trade.owned("instances", id, a.id(), false);
    return Map.of("source", "SIMULATED", "samples", List.of(), "message", "暂无监控数据，不生成虚构使用率");
  }
}
