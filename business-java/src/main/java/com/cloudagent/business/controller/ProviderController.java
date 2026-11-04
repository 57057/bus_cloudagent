package com.cloudagent.business.controller;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.service.*;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** ProviderController：仅处理 HTTP 参数、可信身份和响应；业务规则在 Service。 */
@RestController
public class ProviderController {
  private final ProviderService providers;

  public ProviderController(ProviderService providers) {
    this.providers = providers;
  }

  @GetMapping("/api/admin/providers")
  public Object providers(HttpServletRequest r) {
    return ApiFilter.result(providers.list(ApiFilter.actor(r)));
  }

  @PutMapping("/api/admin/providers")
  public Object provider(@Valid @RequestBody Provider d, HttpServletRequest r) {
    return ApiFilter.result(providers.save(ApiFilter.actor(r), d));
  }

  @PostMapping("/api/admin/providers/aliyun/dry-run")
  public Object dryRun(HttpServletRequest r) {
    return ApiFilter.result(providers.dryRun(ApiFilter.actor(r)));
  }

  @PostMapping("/api/orders/{id}/sandbox-payment")
  public Object sandbox(@PathVariable String id, HttpServletRequest r) {
    return ApiFilter.result(providers.prepare(ApiFilter.actor(r), id));
  }

  @GetMapping("/api/payments/{id}/sandbox-qr")
  public Object qr(@PathVariable String id, HttpServletRequest r) {
    return ApiFilter.result(providers.qr(ApiFilter.actor(r), id));
  }

  @PostMapping("/api/payments/callback/alipay-sandbox")
  public String alipayCallback(@RequestParam Map<String, String> values) {
    providers.callback(values);
    return "success";
  }
}
