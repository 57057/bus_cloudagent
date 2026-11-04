package com.cloudagent.business.controller;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.service.*;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** ContactController：仅处理 HTTP 参数、可信身份和响应；业务规则在 Service。 */
@RestController
public class ContactController {
  private final DeliveryService delivery;

  public ContactController(DeliveryService delivery) {
    this.delivery = delivery;
  }

  @GetMapping("/api/me/contact")
  public Object contact(HttpServletRequest r) {
    return ApiFilter.result(delivery.contact(ApiFilter.actor(r)));
  }

  @PostMapping("/api/me/contact/request")
  public Object requestContact(@Valid @RequestBody Contact d, HttpServletRequest r) {
    return ApiFilter.result(delivery.requestBind(ApiFilter.actor(r), d));
  }

  @PostMapping("/api/me/contact/verify")
  public Object verifyContact(@Valid @RequestBody Verify d, HttpServletRequest r) {
    return ApiFilter.result(delivery.verifyBind(ApiFilter.actor(r), d));
  }

  @PostMapping("/api/auth/password-reset/request")
  public Object requestReset(@Valid @RequestBody Contact d, HttpServletRequest request) {
    return ApiFilter.result(delivery.requestReset(d, request.getRemoteAddr()));
  }

  @PostMapping("/api/auth/password-reset/confirm")
  public Object reset(@Valid @RequestBody Reset d) {
    return ApiFilter.result(delivery.reset(d));
  }

  @GetMapping("/api/admin/deliveries")
  public Object deliveries(HttpServletRequest r) {
    return ApiFilter.result(delivery.deliveries(ApiFilter.actor(r)));
  }

  @GetMapping("/api/admin/deliveries/inbox")
  public Object inbox(HttpServletRequest r) {
    return ApiFilter.result(delivery.inbox(ApiFilter.actor(r)));
  }

  @PostMapping("/api/admin/deliveries/{id}/retry")
  public Object retryDelivery(
      @PathVariable String id, @Valid @RequestBody Decision d, HttpServletRequest r) {
    delivery.retry(ApiFilter.actor(r), id, d.reason());
    return ApiFilter.result(null);
  }
}
