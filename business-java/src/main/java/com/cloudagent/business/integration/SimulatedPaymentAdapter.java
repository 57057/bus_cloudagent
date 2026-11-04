package com.cloudagent.business.integration;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.repository.SimulatedPaymentRepository;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/**
 * 模拟支付渠道的独立台账，不会访问真实银行或发生真实扣款。 REQUIRES_NEW 表示通过 Spring 代理调用时挂起外层事务，使用独立事务提交渠道结果。
 * 这样可模拟“渠道已经成功，但商户自己的事务失败/进程中断”的场景。 同类内部 create/query 调用不会再次触发代理；它们参加当前这次适配器调用的事务。
 */
@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SimulatedPaymentAdapter implements PaymentAdapter {

  private final SimulatedPaymentRepository repository;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public SimulatedPaymentAdapter(SimulatedPaymentRepository repository) {

    this.repository = repository;
  }

  /** 按支付单ID插入独立渠道记录，已存在则查询原记录，避免重复生成渠道请求号。 */
  public Map<String, Object> create(String payment, long amount) {
    repository.insertProviderPayment(payment, "sim-pay-" + payment, amount, now());
    return query(payment);
  }

  /** 本地测试控制渠道成功/失败/取消结果。已经成功的支付不可降级成失败；商户订单仍由回调或补偿查询处理。 */
  public Map<String, Object> settle(String payment, long amount, String outcome) {
    create(payment, amount);
    require(
        !text(query(payment), "state").equals("SUCCEEDED") || outcome.equals("SUCCEEDED"),
        409,
        "PAYMENT_FINAL",
        "成功付款不能改成失败");
    repository.settleProviderPayment(outcome, now(), payment);
    return query(payment);
  }

  /** 只读取模拟渠道台账，不根据浏览器一句“支付成功”推断付款事实。 */
  public Map<String, Object> query(String payment) {
    return repository.findProviderPayment(payment);
  }

  /** 先确认渠道确实已付款，再按退款业务号插入/查询同一条退款结果，并检查重复请求的金额一致。 */
  public String refund(String refund, String payment, long amount) {
    var p = query(payment);
    require(text(p, "state").equals("SUCCEEDED"), 409, "PAYMENT_NOT_SETTLED", "模拟渠道未确认付款");
    repository.insertProviderRefund(refund, "sim-refund-" + refund, amount);
    var r = repository.findProviderRefund(refund);
    require(num(r, "amount_cents") == amount, 409, "REFUND_CONFLICT", "退款幂等参数冲突");
    return text(r, "transaction_id");
  }
}
