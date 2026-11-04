package com.cloudagent.business.integration;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.repository.ProviderRepository;
import com.cloudagent.business.service.ProviderService;
import java.util.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 根据服务端持久化的渠道分派，模拟 settle 永远不能替支付宝生成成功事实。 */
@Component
@Primary
public class RoutingPaymentAdapter implements PaymentAdapter {
  private final SimulatedPaymentAdapter simulated;
  private final ProviderService providers;
  private final ProviderRepository r;
  private final AlipaySandboxClient alipay;

  public RoutingPaymentAdapter(
      SimulatedPaymentAdapter simulated,
      ProviderService providers,
      ProviderRepository r,
      AlipaySandboxClient alipay) {
    this.simulated = simulated;
    this.providers = providers;
    this.r = r;
    this.alipay = alipay;
  }

  private boolean sandbox(String id) {
    return text(r.payment(id), "channel").equals("ALIPAY_SANDBOX");
  }

  public Map<String, Object> create(String id, long amount) {
    if (!sandbox(id)) return simulated.create(id, amount);
    return providers.queryAlipay(id);
  }

  public Map<String, Object> settle(String id, long amount, String outcome) {
    require(!sandbox(id), 409, "REAL_CHANNEL_NO_SIMULATION", "支付宝沙箱只能由权威查询确认");
    return simulated.settle(id, amount, outcome);
  }

  public Map<String, Object> query(String id) {
    return sandbox(id) ? providers.queryAlipay(id) : simulated.query(id);
  }

  public String refund(String refund, String payment, long amount) {
    if (!sandbox(payment)) return simulated.refund(refund, payment, amount);
    var p = providers.queryAlipay(payment);
    require(text(p, "state").equals("SUCCEEDED"), 409, "NOT_PAID", "沙箱付款尚未确认");
    return alipay.refund(refund, payment, amount);
  }
}
