package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.repository.MarketingRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 优惠券及推广规则：优惠额进入报价快照；订单预占、关单归还、付款核销，佣金只基于实际支付并追加退款冲正。 */
@Service
public class MarketingService {
  private final MarketingRepository r;
  private final BusinessEvents events;

  public MarketingService(MarketingRepository r, BusinessEvents events) {
    this.r = r;
    this.events = events;
  }

  public Object coupons(AuthService.Actor a) {
    a.staffOnly();
    return r.coupons();
  }

  @Transactional
  public Object createCoupon(AuthService.Actor a, Coupon d) {
    a.staffOnly();
    require(
        d.ends_at().isAfter(d.starts_at()) && d.ends_at().isAfter(Instant.now()),
        400,
        "INVALID_TIME",
        "优惠券有效期无效");
    require(
        d.discount_cents() <= 100000000 && d.minimum_cents() <= 100000000,
        400,
        "INVALID_AMOUNT",
        "金额超出范围");
    String id = id();
    r.insertCoupon(
        id,
        d.code().strip().toUpperCase(Locale.ROOT),
        d.name().strip(),
        d.minimum_cents(),
        d.discount_cents(),
        d.capacity(),
        d.active(),
        time(d.starts_at()),
        time(d.ends_at()),
        now());
    events.audit(a.id(), "COUPON_CREATE", id, d.reason());
    return Map.of("id", id);
  }

  @Transactional
  public void toggle(AuthService.Actor a, String id, boolean active, String reason) {
    a.staffOnly();
    require(r.toggleCoupon(id, active) == 1, 404, "NOT_FOUND", "优惠券不存在");
    events.audit(a.id(), "COUPON_STATUS", id, reason);
  }

  @Transactional
  public Object claim(AuthService.Actor a, Claim d) {
    var c = r.lockCoupon(d.code().strip().toUpperCase(Locale.ROOT));
    checkTime(c);
    var old = r.existingGrant(a.id(), c.get("id"));
    if (!old.isEmpty()) return old.get(0);
    require(r.grantCount(c.get("id")) < num(c, "capacity"), 409, "COUPON_EXHAUSTED", "优惠券已领完");
    String id = id();
    r.grant(id, a.id(), c.get("id"), now());
    return Map.of("id", id, "state", "AVAILABLE");
  }

  public Object wallet(AuthService.Actor a) {
    return r.wallet(a.id());
  }

  private void checkTime(Map<String, Object> c) {
    require(
        bool(c.get("active"))
            && !instant(c.get("starts_at")).isAfter(Instant.now())
            && instant(c.get("ends_at")).isAfter(Instant.now()),
        409,
        "COUPON_UNAVAILABLE",
        "优惠券未生效、已停用或已过期");
  }

  /** 报价只验证而不占券；下单会重新锁券。至少保留1分钱，不引入零元支付旁路。 */
  public long discount(AuthService.Actor a, String grant, long original) {
    var c = r.lockGrant(grant, a.id());
    checkTime(c);
    require(
        text(c, "state").equals("AVAILABLE") && original >= num(c, "minimum_cents"),
        409,
        "COUPON_UNAVAILABLE",
        "优惠券不可用或未达到门槛");
    return Math.min(num(c, "discount_cents"), original - 1);
  }

  /** 与原订单创建事务一起保存归因；旧链接保留当时的佣金版本，停用活动后拒绝新归因。 */
  public void attach(
      AuthService.Actor a,
      String order,
      Map<String, Object> snapshot,
      long original,
      String product) {
    String grant = Objects.toString(snapshot.get("coupon_grant_id"), "");
    long discount = ((Number) snapshot.getOrDefault("coupon_discount_cents", 0L)).longValue();
    if (!grant.isBlank()) {
      require(discount(a, grant, original) == discount, 409, "COUPON_CHANGED", "优惠券规则已变化，请重新报价");
      require(r.reserve(grant, a.id(), order) == 1, 409, "COUPON_UNAVAILABLE", "优惠券已被占用");
    }
    String promoter = null;
    int rate = 0;
    Object version = null;
    String link = Objects.toString(snapshot.get("promotion_link"), "");
    if (!link.isBlank()) {
      var l = r.activeLink(link);
      require(
          bool(l.get("active"))
              && text(l, "product_id").equals(product)
              && !text(l, "user_id").equals(a.id()),
          409,
          "INVALID_ATTRIBUTION",
          "活动已停用、产品不符或不能自购返佣");
      promoter = text(l, "user_id");
      rate = (int) num(l, "rate_bps");
      version = l.get("campaign_version");
    }
    r.benefits(order, grant.isBlank() ? null : grant, discount, promoter, rate, version);
  }

  public void release(Object order) {
    r.release(order);
  }

  public void paid(Map<String, Object> order, boolean eligible) {
    if (eligible) r.use(order.get("id"));
    var rows = r.benefit(order.get("id"));
    if (!eligible || rows.isEmpty() || rows.get(0).get("promoter_id") == null) return;
    var b = rows.get(0);
    long amount = Math.multiplyExact(num(order, "total_cents"), num(b, "rate_bps")) / 10000;
    r.ledger(
        id(),
        b.get("promoter_id"),
        order.get("id"),
        "paid:" + order.get("id"),
        amount,
        "PAYMENT",
        now());
  }

  /** 使用累计退款重新计算目标净佣金，差额冲正避免多次部分退款产生舍入套利。 */
  public void refunded(Map<String, Object> order, String refundId) {
    r.flagInvoice(order.get("id"));
    var rows = r.benefit(order.get("id"));
    if (rows.isEmpty() || rows.get(0).get("promoter_id") == null) return;
    var b = rows.get(0);
    long target =
        Math.multiplyExact(
                Math.max(0, num(order, "total_cents") - num(order, "refunded_cents")),
                num(b, "rate_bps"))
            / 10000;
    long delta = target - r.netCommission(order.get("id"));
    if (delta != 0)
      r.ledger(
          id(),
          b.get("promoter_id"),
          order.get("id"),
          "refund:" + refundId,
          delta,
          "REFUND",
          now());
  }

  public Object campaigns(AuthService.Actor a, boolean staff) {
    if (staff) a.staffOnly();
    return r.campaigns(staff);
  }

  @Transactional
  public Object saveCampaign(AuthService.Actor a, Campaign d) {
    a.staffOnly();
    require(r.productExists(d.product_id()) == 1, 404, "NOT_FOUND", "产品不存在");
    String id = d.id() == null || d.id().isBlank() ? id() : d.id();
    r.saveCampaign(id, d.product_id(), d.title(), d.rate_bps(), d.active(), now());
    events.audit(a.id(), "CAMPAIGN_SAVE", id, d.reason());
    return r.campaign(id);
  }

  @Transactional
  public Object link(AuthService.Actor a, String campaign) {
    var c = r.campaign(campaign);
    require(bool(c.get("active")), 409, "CAMPAIGN_DISABLED", "活动已停用");
    r.link(id(), a.id(), campaign, c.get("version"), c.get("product_id"), c.get("rate_bps"), now());
    var l = new LinkedHashMap<>(r.userLink(a.id(), campaign, c.get("version")));
    l.put("path", "/#/products/" + c.get("product_id") + "?ref=" + l.get("id"));
    return l;
  }

  @Transactional
  public Object materials(AuthService.Actor a, String product) {
    for (var c : r.campaigns(false))
      if (text(c, "product_id").equals(product)) return link(a, text(c, "id"));
    return Map.of("items", List.of(), "message", "当前产品没有可用推广活动");
  }

  public Object commissions(AuthService.Actor a, boolean staff) {
    if (staff) {
      a.staffOnly();
      return r.allCommissions();
    }
    return r.commissions(a.id());
  }

  @Transactional
  public Object settle(AuthService.Actor a, String order, String reason) {
    a.adminOnly();
    var o = r.lockOrder(order);
    var bs = r.benefit(order);
    require(
        !bs.isEmpty() && !bool(bs.get(0).get("settled")), 409, "ALREADY_SETTLED", "没有可结算归因或已结算");
    require(
        Set.of("COMPLETED", "PARTIALLY_FULFILLED").contains(text(o, "state"))
            && instant(o.get("created_at")).plus(Duration.ofDays(8)).isBefore(Instant.now())
            && r.pendingRefunds(order) == 0
            && r.unsettledItems(order) == 0,
        409,
        "COMMISSION_FROZEN",
        "佣金冻结期或售后处理中，暂不可结算");
    long amount = r.netCommission(order);
    require(amount > 0, 409, "NO_COMMISSION", "没有正佣金");
    r.settle(order, text(bs.get(0), "promoter_id"), amount, reason, now());
    events.audit(a.id(), "SIMULATED_COMMISSION_SETTLEMENT", order, reason);
    return Map.of("amount_cents", amount, "mode", "SIMULATED");
  }
}
