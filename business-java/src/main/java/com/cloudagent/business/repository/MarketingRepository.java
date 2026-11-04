package com.cloudagent.business.repository;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 优惠券、推广归因和佣金账的数据访问。行锁由业务事务持有，退款通过追加冲正保留历史。 */
@Repository
public class MarketingRepository {
  private final SqlExecutor s;

  public MarketingRepository(SqlExecutor s) {
    this.s = s;
  }

  public List<Map<String, Object>> coupons() {
    return s.list("SELECT * FROM p1_coupons ORDER BY created_at DESC LIMIT 200");
  }

  public Map<String, Object> lockCoupon(String code) {
    return s.one("SELECT * FROM p1_coupons WHERE code=? FOR UPDATE", code);
  }

  public void insertCoupon(
      String id,
      String code,
      String name,
      long min,
      long discount,
      int capacity,
      boolean active,
      Timestamp start,
      Timestamp end,
      Timestamp now) {
    s.run(
        "INSERT INTO"
            + " p1_coupons(id,code,name,minimum_cents,discount_cents,capacity,active,starts_at,ends_at,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        code,
        name,
        min,
        discount,
        capacity,
        active,
        start,
        end,
        now);
  }

  public int toggleCoupon(String id, boolean active) {
    return s.run("UPDATE p1_coupons SET active=?,version=version+1 WHERE id=?", active, id);
  }

  public long grantCount(Object coupon) {
    return s.count("SELECT COUNT(*) FROM p1_coupon_grants WHERE coupon_id=?", coupon);
  }

  public List<Map<String, Object>> existingGrant(String user, Object coupon) {
    return s.list("SELECT * FROM p1_coupon_grants WHERE user_id=? AND coupon_id=?", user, coupon);
  }

  public void grant(String id, String user, Object coupon, Timestamp now) {
    s.run(
        "INSERT INTO p1_coupon_grants(id,user_id,coupon_id,created_at) VALUES(?,?,?,?)",
        id,
        user,
        coupon,
        now);
  }

  public List<Map<String, Object>> wallet(String user) {
    return s.list(
        "SELECT"
            + " g.id,g.state,g.order_id,c.code,c.name,c.minimum_cents,c.discount_cents,c.active,c.ends_at"
            + " FROM p1_coupon_grants g JOIN p1_coupons c ON c.id=g.coupon_id WHERE g.user_id=?"
            + " ORDER BY g.created_at DESC LIMIT 200",
        user);
  }

  public Map<String, Object> lockGrant(String id, String user) {
    return s.one(
        "SELECT"
            + " g.*,c.name,c.minimum_cents,c.discount_cents,c.active,c.starts_at,c.ends_at,c.version"
            + " FROM p1_coupon_grants g JOIN p1_coupons c ON c.id=g.coupon_id WHERE g.id=? AND"
            + " g.user_id=? FOR UPDATE",
        id,
        user);
  }

  public int reserve(String grant, String user, String order) {
    return s.run(
        "UPDATE p1_coupon_grants SET state='RESERVED',order_id=? WHERE id=? AND user_id=? AND"
            + " state='AVAILABLE'",
        order,
        grant,
        user);
  }

  public void release(Object order) {
    s.run(
        "UPDATE p1_coupon_grants SET state='AVAILABLE',order_id=NULL WHERE order_id=? AND"
            + " state='RESERVED'",
        order);
  }

  public void use(Object order) {
    s.run("UPDATE p1_coupon_grants SET state='USED' WHERE order_id=? AND state='RESERVED'", order);
  }

  public void saveCampaign(
      String id, String product, String title, int rate, boolean active, Timestamp now) {
    s.run(
        "INSERT INTO p1_campaigns VALUES(?,?,?,?,1,?,?) ON DUPLICATE KEY UPDATE"
            + " title=VALUES(title),rate_bps=VALUES(rate_bps),active=VALUES(active),version=version+1",
        id,
        product,
        title,
        rate,
        active,
        now);
  }

  public Map<String, Object> campaign(String id) {
    return s.one("SELECT * FROM p1_campaigns WHERE id=? FOR UPDATE", id);
  }

  public List<Map<String, Object>> campaigns(boolean staff) {
    return staff
        ? s.list("SELECT * FROM p1_campaigns ORDER BY created_at DESC LIMIT 200")
        : s.list(
            "SELECT c.* FROM p1_campaigns c JOIN p0_products p ON p.id=c.product_id WHERE"
                + " c.active=TRUE AND p.active=TRUE ORDER BY c.created_at DESC LIMIT 200");
  }

  public void link(
      String id,
      String user,
      String campaign,
      Object version,
      Object product,
      Object rate,
      Timestamp now) {
    s.run(
        "INSERT IGNORE INTO p1_promotion_links VALUES(?,?,?,?,?,?,?)",
        id,
        user,
        campaign,
        version,
        product,
        rate,
        now);
  }

  public Map<String, Object> userLink(String user, String campaign, Object version) {
    return s.one(
        "SELECT * FROM p1_promotion_links WHERE user_id=? AND campaign_id=? AND campaign_version=?",
        user,
        campaign,
        version);
  }

  public Map<String, Object> activeLink(String id) {
    return s.one(
        "SELECT l.*,c.active FROM p1_promotion_links l JOIN p1_campaigns c ON c.id=l.campaign_id"
            + " WHERE l.id=?",
        id);
  }

  public void benefits(
      String order, String grant, long discount, String promoter, int rate, Object version) {
    s.run(
        "INSERT INTO"
            + " p1_order_benefits(order_id,coupon_grant_id,discount_cents,promoter_id,rate_bps,campaign_version)"
            + " VALUES(?,?,?,?,?,?)",
        order,
        grant,
        discount,
        promoter,
        rate,
        version);
  }

  public List<Map<String, Object>> benefit(Object order) {
    return s.list("SELECT * FROM p1_order_benefits WHERE order_id=? FOR UPDATE", order);
  }

  public void ledger(
      String id, Object user, Object order, String event, long amount, String kind, Timestamp now) {
    s.run(
        "INSERT IGNORE INTO p1_commission_ledger VALUES(?,?,?,?,?,?,?)",
        id,
        user,
        order,
        event,
        amount,
        kind,
        now);
  }

  public long netCommission(Object order) {
    return s.count(
        "SELECT COALESCE(SUM(amount_cents),0) FROM p1_commission_ledger WHERE order_id=?", order);
  }

  public List<Map<String, Object>> commissions(String user) {
    return s.list(
        "SELECT l.*,b.settled FROM p1_commission_ledger l JOIN p1_order_benefits b ON"
            + " b.order_id=l.order_id WHERE l.user_id=? ORDER BY l.created_at DESC LIMIT 200",
        user);
  }

  public List<Map<String, Object>> allCommissions() {
    return s.list(
        "SELECT l.*,b.settled FROM p1_commission_ledger l JOIN p1_order_benefits b ON"
            + " b.order_id=l.order_id ORDER BY l.created_at DESC LIMIT 200");
  }

  public Map<String, Object> lockOrder(String order) {
    return s.one("SELECT * FROM p0_orders WHERE id=? FOR UPDATE", order);
  }

  public long pendingRefunds(String order) {
    return s.count(
        "SELECT COUNT(*) FROM p0_refunds WHERE order_id=? AND state NOT IN"
            + " ('REJECTED','SUCCEEDED')",
        order);
  }

  public long unsettledItems(String order) {
    return s.count(
        "SELECT COUNT(*) FROM p0_items i JOIN p0_orders o ON o.id=i.order_id LEFT JOIN p0_instances"
            + " r ON r.id=i.instance_id WHERE i.order_id=? AND i.refunded=FALSE AND"
            + " (i.state<>'SUCCEEDED' OR (o.kind='NEW_PURCHASE' AND (r.id IS NULL OR (r.state IN"
            + " ('RUNNING','STOPPED','SUSPENDED') AND"
            + " TIMESTAMPDIFF(SECOND,r.starts_at,UTC_TIMESTAMP())<(CAST(JSON_UNQUOTE(JSON_EXTRACT(o.snapshot,'$.refund_days'))"
            + " AS UNSIGNED)+1)*86400))))",
        order);
  }

  public void settle(String order, String user, long amount, String reason, Timestamp now) {
    s.run(
        "INSERT INTO p1_commission_settlements VALUES(?,?,?,?,'SIMULATED',?)",
        order,
        user,
        amount,
        reason,
        now);
    s.run("UPDATE p1_order_benefits SET settled=TRUE WHERE order_id=?", order);
  }

  public long productExists(String id) {
    return s.count("SELECT COUNT(*) FROM p0_products WHERE id=?", id);
  }

  public void flagInvoice(Object order) {
    s.run(
        "UPDATE p1_invoices SET"
            + " state=IF(state='ISSUED','NEEDS_RED','REJECTED'),review_reason='退款成功，需重算开票额度或红冲'"
            + " WHERE order_id=? AND state IN ('ISSUED','SUBMITTED')",
        order);
  }
}
