package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * SimulatedPaymentAdapter的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC
 * 类型；参数数量固定，值不拼接进 SQL。
 */
@Repository
public class SimulatedPaymentRepository {
  private final SqlExecutor s;

  public SimulatedPaymentRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 执行参数绑定的写入并返回影响行数：insertProviderPayment。锁和条件更新在调用方业务事务中生效。 */
  public int insertProviderPayment(
      Object paymentId, Object transactionId, Object amountCents, Object updatedAt) {
    return s.run(
        "INSERT IGNORE INTO p0_provider_payments VALUES(?,?,?,'CREATED',?)",
        paymentId,
        transactionId,
        amountCents,
        updatedAt);
  }

  /** 执行参数绑定的写入并返回影响行数：settleProviderPayment。锁和条件更新在调用方业务事务中生效。 */
  public int settleProviderPayment(Object outcome, Object updatedAt, Object payment) {
    return s.run(
        "UPDATE p0_provider_payments SET state=?,updated_at=? WHERE payment_id=?",
        outcome,
        updatedAt,
        payment);
  }

  /** 读取单条记录（不存在时抛出404）：findProviderPayment。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findProviderPayment(Object payment) {
    return s.one("SELECT * FROM p0_provider_payments WHERE payment_id=?", payment);
  }

  /** 执行参数绑定的写入并返回影响行数：insertProviderRefund。锁和条件更新在调用方业务事务中生效。 */
  public int insertProviderRefund(Object refundId, Object transactionId, Object amountCents) {
    return s.run(
        "INSERT IGNORE INTO p0_provider_refunds VALUES(?,?,?,'SUCCEEDED')",
        refundId,
        transactionId,
        amountCents);
  }

  /** 读取单条记录（不存在时抛出404）：findProviderRefund。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findProviderRefund(Object refund) {
    return s.one("SELECT * FROM p0_provider_refunds WHERE refund_id=?", refund);
  }
}
