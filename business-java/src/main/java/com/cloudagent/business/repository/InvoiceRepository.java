package com.cloudagent.business.repository;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 发票申请和运营统计的数据访问；报表先分别聚合，避免多表连接把订单金额重复累计。 */
@Repository
public class InvoiceRepository {
  private final SqlExecutor s;

  public InvoiceRepository(SqlExecutor s) {
    this.s = s;
  }

  public Map<String, Object> lockOrder(String id, String user) {
    return s.one("SELECT * FROM p0_orders WHERE id=? AND user_id=? FOR UPDATE", id, user);
  }

  public long paid(String id) {
    return s.count("SELECT COUNT(*) FROM p0_payments WHERE order_id=? AND state='SUCCEEDED'", id);
  }

  public List<Map<String, Object>> byOrder(String id) {
    return s.list("SELECT * FROM p1_invoices WHERE order_id=? FOR UPDATE", id);
  }

  public Map<String, Object> lockInvoice(String id) {
    return s.one("SELECT * FROM p1_invoices WHERE id=? FOR UPDATE", id);
  }

  public Map<String, Object> findInvoice(String id) {
    return s.one("SELECT * FROM p1_invoices WHERE id=?", id);
  }

  public Map<String, Object> order(String id) {
    return s.one("SELECT * FROM p0_orders WHERE id=? FOR UPDATE", id);
  }

  public void apply(
      String id, String user, String order, String title, String tax, long amount, Timestamp now) {
    s.run(
        "INSERT INTO"
            + " p1_invoices(id,user_id,order_id,title,tax_number,amount_cents,state,created_at)"
            + " VALUES(?,?,?,?,?,?,'SUBMITTED',?) ON DUPLICATE KEY UPDATE"
            + " title=VALUES(title),tax_number=VALUES(tax_number),amount_cents=VALUES(amount_cents),state='SUBMITTED',review_reason=NULL,invoice_number=NULL",
        id,
        user,
        order,
        title,
        tax,
        amount,
        now);
  }

  public void review(String id, String state, String number, String reason) {
    s.run(
        "UPDATE p1_invoices SET state=?,invoice_number=?,review_reason=? WHERE id=?",
        state,
        number,
        reason,
        id);
  }

  public List<Map<String, Object>> invoices(String user) {
    return user == null
        ? s.list("SELECT * FROM p1_invoices ORDER BY created_at DESC LIMIT 200")
        : s.list(
            "SELECT * FROM p1_invoices WHERE user_id=? ORDER BY created_at DESC LIMIT 200", user);
  }

  public Map<String, Object> paymentReport(Timestamp from, Timestamp to) {
    return s.one(
        "SELECT COUNT(*) AS payments,COALESCE(SUM(amount_cents),0) AS paid_cents FROM p0_payments"
            + " WHERE state='SUCCEEDED' AND created_at>=? AND created_at<?",
        from,
        to);
  }

  public Map<String, Object> refundReport(Timestamp from, Timestamp to) {
    return s.one(
        "SELECT COUNT(*) AS refunds,COALESCE(SUM(amount_cents),0) AS refunded_cents FROM p0_refunds"
            + " WHERE state='SUCCEEDED' AND created_at>=? AND created_at<?",
        from,
        to);
  }

  public List<Map<String, Object>> sales(Timestamp from, Timestamp to) {
    return s.list(
        "SELECT o.sku_id,o.kind,COUNT(*) AS orders,COALESCE(SUM(o.total_cents),0) AS"
            + " gross_cents,COALESCE(SUM(o.refunded_cents),0) AS refunded_cents FROM p0_orders o"
            + " WHERE EXISTS(SELECT 1 FROM p0_payments p WHERE p.order_id=o.id AND"
            + " p.state='SUCCEEDED') AND o.created_at>=? AND o.created_at<? GROUP BY"
            + " o.sku_id,o.kind ORDER BY gross_cents DESC LIMIT 200",
        from,
        to);
  }
}
