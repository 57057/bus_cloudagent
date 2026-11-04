package com.cloudagent.business.integration;

import java.util.Map;

/**
 * 支付渠道的接口契约，业务层依赖接口，模拟渠道提供具体实现。 create 创建/查询支付请求，settle 是本地模拟渠道的结果控制，query 查询权威结果，refund 退款。
 * 真实渠道接入时必须单独实现商户签名、凭据和回调协议，不能把模拟 settle 当成真实扣款接口。
 */
public interface PaymentAdapter {
  /** 创建或找回同一支付业务号的渠道请求，不表示已付款。 */
  Map<String, Object> create(String payment, long amount);

  /** 本地模拟渠道控制结果；真实渠道应由支付系统自己的结果确认流程替代。 */
  Map<String, Object> settle(String payment, long amount, String outcome);

  /** 查询渠道权威状态，支持回调丢失后的补偿。 */
  Map<String, Object> query(String payment);

  /** 按稳定退款业务号执行/查询同一笔退款。 */
  String refund(String refund, String payment, long amount);
}
