package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.dto.P1Requests.AutoRenew;
import com.cloudagent.business.integration.PaymentAdapter;
import com.cloudagent.business.repository.RenewalRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 自动续费只有用户逐实例明确授权后开启，限定周期和单次金额；当前扣款授权只支持模拟渠道，未签代扣协议绝不调用真实扣款。 */
@Service
public class RenewalService {
  private final RenewalRepository r;
  private final TradeService trade;
  private final PaymentAdapter payment;
  private final BusinessEvents events;
  private final TransactionTemplate tx;
  private final boolean demo;

  public RenewalService(
      RenewalRepository r,
      TradeService trade,
      PaymentAdapter payment,
      BusinessEvents events,
      PlatformTransactionManager tm,
      @Value("${platform.demo}") boolean demo) {
    this.r = r;
    this.trade = trade;
    this.payment = payment;
    this.events = events;
    this.tx = new TransactionTemplate(tm);
    this.demo = demo;
  }

  public Object list(AuthService.Actor a) {
    return r.list(a.id());
  }

  @Transactional
  public Object save(AuthService.Actor a, String instance, AutoRenew d) {
    trade.owned("instances", instance, a.id(), true);
    require(
        !d.enabled() || demo && d.confirmed(),
        400,
        "AUTHORIZATION_REQUIRED",
        "开启前需确认模拟自动扣款授权；真实代扣暂未签约");
    require(Set.of(1, 3, 12).contains(d.months()), 400, "INVALID_MONTHS", "周期为1/3/12个月");
    r.save(instance, a.id(), d.enabled(), d.months(), d.max_cents(), now());
    events.audit(
        a.id(),
        d.enabled() ? "AUTO_RENEW_AUTHORIZED" : "AUTO_RENEW_CANCELLED",
        instance,
        "仅模拟渠道；单次金额上限分：" + d.max_cents());
    return r.lock(instance);
  }

  /** 先将续费订单/支付单提交落库，再在独立步骤模拟付款；崩溃重试只能使用已保存的支付号。 */
  public void poll() {
    if (!demo) return;
    for (var item : r.due(time(Instant.now().plus(Duration.ofDays(3))))) {
      String instance = text(item, "instance_id");
      try {
        var p =
            tx.execute(
                st -> {
                  var initial = r.find(instance);
                  r.lockUser(text(initial, "user_id"));
                  var i = r.instance(instance);
                  var a = r.lock(instance);
                  if (!bool(a.get("enabled"))) return null;
                  String cycle = instant(i.get("expires_at")).toString();
                  if (text(a, "state").equals("PROCESSING")) {
                    var existing = r.payment(text(a, "last_order_id"));
                    if (Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(text(existing, "state"))) {
                      r.state(
                          instance,
                          text(existing, "state").equals("SUCCEEDED") ? "SUCCEEDED" : "FAILED",
                          null);
                      return null;
                    }
                    return existing;
                  }
                  if (cycle.equals(text(a, "last_cycle"))) return null;
                  var actor =
                      new AuthService.Actor(text(a, "user_id"), "USER", "automatic-renewal");
                  @SuppressWarnings("unchecked")
                  var q =
                      (Map<String, Object>)
                          trade.quote(actor, Map.of("months", a.get("months")), instance);
                  require(
                      num(q, "total_cents") <= num(a, "max_cents"),
                      409,
                      "AUTO_RENEW_LIMIT",
                      "续费金额超过授权上限");
                  @SuppressWarnings("unchecked")
                  var order =
                      (Map<String, Object>)
                          trade.createOrder(
                              actor,
                              Map.of("quote_id", q.get("id")),
                              "auto-" + AuthService.hash(instance + cycle));
                  @SuppressWarnings("unchecked")
                  var pay = (Map<String, Object>) trade.newPayment(actor, text(order, "id"));
                  r.cycle(instance, cycle, text(order, "id"));
                  return pay;
                });
        if (p != null) {
          require(text(p, "channel").equals("SIMULATED"), 409, "NO_REAL_MANDATE", "没有真实渠道代扣协议");
          payment.settle(text(p, "id"), num(p, "amount_cents"), "SUCCEEDED");
        }
      } catch (Exception e) {
        tx.executeWithoutResult(
            st -> {
              var i = r.instance(instance);
              var a = r.lock(instance);
              r.cycle(instance, instant(i.get("expires_at")).toString(), text(a, "last_order_id"));
              r.state(instance, "FAILED", e instanceof ApiError ? e.getMessage() : "续费暂不可用");
              events.notice(
                  "auto-failed:" + instance + ":" + i.get("expires_at"),
                  text(a, "user_id"),
                  "自动续费未完成",
                  "请检查金额授权、资源状态并手动续费，或重新授权后重试");
            });
      }
    }
  }
}
