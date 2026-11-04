package com.cloudagent.business.controller;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.service.*;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** RenewalController：仅处理 HTTP 参数、可信身份和响应；业务规则在 Service。 */
@RestController
public class RenewalController {
  private final RenewalService renewals;

  public RenewalController(RenewalService renewals) {
    this.renewals = renewals;
  }

  @GetMapping("/api/auto-renewals")
  public Object renewals(HttpServletRequest r) {
    return ApiFilter.result(renewals.list(ApiFilter.actor(r)));
  }

  @PutMapping("/api/instances/{id}/auto-renewal")
  public Object renewal(
      @PathVariable String id, @Valid @RequestBody AutoRenew d, HttpServletRequest r) {
    return ApiFilter.result(renewals.save(ApiFilter.actor(r), id, d));
  }
}
