package com.cloudagent.business.repository;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 外部渠道配置只保存非秘密参数；支付单渠道来自服务端订单，客户端不能指定权威流水。 */
@Repository
public class ProviderRepository {
  private final SqlExecutor s;

  public ProviderRepository(SqlExecutor s) {
    this.s = s;
  }

  public List<Map<String, Object>> configs() {
    return s.list("SELECT * FROM p1_provider_configs ORDER BY id");
  }

  public List<Map<String, Object>> config(String id) {
    return s.list("SELECT * FROM p1_provider_configs WHERE id=?", id);
  }

  public void save(String id, String mode, String settings, Timestamp now) {
    s.run(
        "INSERT INTO p1_provider_configs VALUES(?,?,?,?,1,?) ON DUPLICATE KEY UPDATE"
            + " mode=VALUES(mode),settings=VALUES(settings),version=version+1,updated_at=VALUES(updated_at)",
        id,
        id,
        mode,
        settings,
        now);
  }

  public Map<String, Object> payment(String id) {
    return s.one("SELECT * FROM p0_payments WHERE id=?", id);
  }

  public Map<String, Object> lockOrder(String order, String user) {
    return s.one("SELECT * FROM p0_orders WHERE id=? AND user_id=? FOR UPDATE", order, user);
  }

  public List<Map<String, Object>> orderPayment(String order) {
    return s.list("SELECT * FROM p0_payments WHERE order_id=?", order);
  }

  public void insertPayment(String id, String order, String user, Object amount, Timestamp now) {
    s.run(
        "INSERT INTO p0_payments(id,order_id,user_id,amount_cents,channel,state,created_at)"
            + " VALUES(?,?,?,?,'ALIPAY_SANDBOX','CREATED',?)",
        id,
        order,
        user,
        amount,
        now);
  }

  public void providerPayment(
      String payment, String transaction, long amount, String state, Timestamp now) {
    s.run(
        "INSERT INTO p0_provider_payments VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " transaction_id=IF(state='SUCCEEDED',transaction_id,VALUES(transaction_id)),state=IF(state='SUCCEEDED',state,VALUES(state)),updated_at=VALUES(updated_at)",
        payment,
        transaction,
        amount,
        state,
        now);
  }

  public List<Map<String, Object>> pendingAlipay() {
    return s.list(
        "SELECT id FROM p0_payments WHERE channel='ALIPAY_SANDBOX' AND state<>'SUCCEEDED' ORDER BY"
            + " created_at DESC LIMIT 50");
  }

  public void qr(String payment, String qr) {
    s.run(
        "INSERT INTO p1_provider_qr VALUES(?,?) ON DUPLICATE KEY UPDATE qr_code=VALUES(qr_code)",
        payment,
        qr);
  }

  public List<Map<String, Object>> qr(String payment) {
    return s.list("SELECT * FROM p1_provider_qr WHERE payment_id=?", payment);
  }
}
