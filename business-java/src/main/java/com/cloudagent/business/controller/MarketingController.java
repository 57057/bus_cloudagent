package com.cloudagent.business.controller;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.service.*;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** MarketingController：仅处理 HTTP 参数、可信身份和响应；业务规则在 Service。 */
@RestController
public class MarketingController {
  private final MarketingService marketing;

  public MarketingController(MarketingService marketing) {
    this.marketing = marketing;
  }

  @GetMapping("/api/coupons")
  public Object wallet(HttpServletRequest r) {
    return ApiFilter.result(marketing.wallet(ApiFilter.actor(r)));
  }

  @PostMapping("/api/coupons/claim")
  public Object claim(@Valid @RequestBody Claim d, HttpServletRequest r) {
    return ApiFilter.result(marketing.claim(ApiFilter.actor(r), d));
  }

  @GetMapping("/api/admin/coupons")
  public Object coupons(HttpServletRequest r) {
    return ApiFilter.result(marketing.coupons(ApiFilter.actor(r)));
  }

  @PostMapping("/api/admin/coupons")
  public Object coupon(@Valid @RequestBody Coupon d, HttpServletRequest r) {
    return ApiFilter.result(marketing.createCoupon(ApiFilter.actor(r), d));
  }

  @PatchMapping("/api/admin/coupons/{id}")
  public Object couponState(
      @PathVariable String id, @Valid @RequestBody Decision d, HttpServletRequest r) {
    marketing.toggle(ApiFilter.actor(r), id, d.action().equals("ENABLE"), d.reason());
    return ApiFilter.result(null);
  }

  @GetMapping("/api/promotion/campaigns")
  public Object campaigns(HttpServletRequest r) {
    return ApiFilter.result(marketing.campaigns(ApiFilter.actor(r), false));
  }

  @GetMapping("/api/admin/campaigns")
  public Object adminCampaigns(HttpServletRequest r) {
    return ApiFilter.result(marketing.campaigns(ApiFilter.actor(r), true));
  }

  @PostMapping("/api/admin/campaigns")
  public Object campaign(@Valid @RequestBody Campaign d, HttpServletRequest r) {
    return ApiFilter.result(marketing.saveCampaign(ApiFilter.actor(r), d));
  }

  @PostMapping("/api/promotion/campaigns/{id}/links")
  public Object link(@PathVariable String id, HttpServletRequest r) {
    return ApiFilter.result(marketing.link(ApiFilter.actor(r), id));
  }

  @GetMapping("/api/promotion/commissions")
  public Object commissions(HttpServletRequest r) {
    return ApiFilter.result(marketing.commissions(ApiFilter.actor(r), false));
  }

  @GetMapping("/api/admin/commissions")
  public Object adminCommissions(HttpServletRequest r) {
    return ApiFilter.result(marketing.commissions(ApiFilter.actor(r), true));
  }

  @PostMapping("/api/admin/commissions/{order}/settle")
  public Object settle(
      @PathVariable String order, @Valid @RequestBody Decision d, HttpServletRequest r) {
    return ApiFilter.result(marketing.settle(ApiFilter.actor(r), order, d.reason()));
  }
}
