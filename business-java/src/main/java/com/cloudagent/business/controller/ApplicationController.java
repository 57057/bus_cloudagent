package com.cloudagent.business.controller;

import static com.cloudagent.business.web.ApiFilter.*;

import com.cloudagent.business.dto.ApplicationRequests.*;
import com.cloudagent.business.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 用户只操作租约内项目；纳管与注册凭据管理仅管理员可用。 */
@RestController
public class ApplicationController {
  @PostMapping("/api/admin/hosts/{id}/adopt-windows-static") public Object adoptWindows(@PathVariable String id,HttpServletRequest r){return result(pool.adoptWindows(actor(r),id));}
  @GetMapping("/api/admin/hosts/{id}/executions")
  public Object executions(@PathVariable String id, HttpServletRequest r) {
    return result(pool.executions(actor(r), id));
  }

  @PostMapping("/api/admin/cleanup/{id}/retry")
  public Object retryCleanup(
      @PathVariable String id, @RequestBody java.util.Map<String, String> d, HttpServletRequest r) {
    return result(pool.retryCleanup(actor(r), id, d.get("reason")));
  }

  @GetMapping("/api/admin/pool-finance")
  public Object finance(HttpServletRequest r) {
    return result(pool.financeReport(actor(r)));
  }

  @PostMapping("/api/admin/hosts/{id}/finance")
  public Object finance(
      @PathVariable String id, @Valid @RequestBody Finance d, HttpServletRequest r) {
    return result(pool.finance(actor(r), id, d));
  }

  @GetMapping("/api/admin/cloud-assets")
  public Object discover(@RequestParam String region, HttpServletRequest r) {
    return result(pool.discover(actor(r), region));
  }

  private final ApplicationService apps;
  private final PoolService pool;

  public ApplicationController(ApplicationService apps, PoolService pool) {
    this.apps = apps;
    this.pool = pool;
  }

  @GetMapping("/api/admin/hosts")
  public Object hosts(HttpServletRequest r) {
    return result(pool.hosts(actor(r)));
  }

  @PostMapping("/api/admin/hosts")
  public Object host(@Valid @RequestBody Host d, HttpServletRequest r) {
    return result(pool.add(actor(r), d));
  }

  @PostMapping("/api/admin/hosts/{id}/registration-tokens")
  public Object token(@PathVariable String id, HttpServletRequest r) {
    return result(pool.token(actor(r), id));
  }

  @PostMapping("/api/admin/hosts/{id}/revoke")
  public Object revoke(@PathVariable String id, HttpServletRequest r) {
    return result(pool.revoke(actor(r), id));
  }

  @GetMapping("/api/allocations")
  public Object allocations(HttpServletRequest r) {
    return result(pool.allocations(actor(r)));
  }

  @GetMapping("/api/projects")
  public Object projects(HttpServletRequest r) {
    return result(apps.projects(actor(r)));
  }

  @GetMapping("/api/application-plans")
  public Object plans(HttpServletRequest r) {
    return result(apps.plans(actor(r)));
  }

  @GetMapping("/api/projects/{id}/evidence")
  public Object evidence(@PathVariable String id, HttpServletRequest r) {
    return result(apps.evidence(actor(r), id));
  }

  @PostMapping("/api/projects/{id}/diagnoses")
  public Object diagnose(@PathVariable String id, HttpServletRequest r) {
    return result(apps.diagnose(actor(r), id));
  }

  @PostMapping("/api/projects")
  public Object project(@Valid @RequestBody Project d, HttpServletRequest r) {
    return result(apps.create(actor(r), d));
  }

  @GetMapping("/api/projects/{id}")
  public Object project(@PathVariable String id, HttpServletRequest r) {
    return result(apps.detail(actor(r), id));
  }

  @GetMapping("/api/projects/{id}/config")
  public Object config(@PathVariable String id, HttpServletRequest r) {
    return result(apps.config(actor(r), id));
  }

  @PostMapping("/api/projects/{id}/config")
  public Object config(
      @PathVariable String id, @Valid @RequestBody Config d, HttpServletRequest r) {
    return result(apps.configure(actor(r), id, d));
  }

  @PostMapping("/api/projects/{id}/plans")
  public Object plan(@PathVariable String id, @Valid @RequestBody Plan d, HttpServletRequest r) {
    return result(apps.prepare(actor(r), id, d));
  }

  @PatchMapping("/api/projects/{id}/welcome")
  public Object welcome(@PathVariable String id, @Valid @RequestBody WelcomeMessage d, HttpServletRequest r) {
    return result(apps.welcome(actor(r), id, d));
  }

  @PostMapping("/api/plans/{id}/cancel")
  public Object dismissPlan(@PathVariable String id, HttpServletRequest r) {
    return result(apps.dismissPlan(actor(r), id));
  }

  @PostMapping("/api/deployments/{id}/cancel")
  public Object cancel(@PathVariable String id, @Valid @RequestBody Cancel d, HttpServletRequest r) {
    return result(apps.cancel(actor(r), id, d));
  }

  @PostMapping("/api/plans/{id}/confirm")
  @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
  public Object confirm(
      @PathVariable String id,
      @Valid @RequestBody Confirm d,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    return result(apps.confirm(actor(r), id, d, key));
  }

  @GetMapping("/api/deployments/{id}")
  public Object deployment(@PathVariable String id, HttpServletRequest r) {
    return result(apps.deployment(actor(r), id));
  }

  @GetMapping("/api/deployments/{id}/events")
  public Object events(
      @PathVariable String id, @RequestParam(defaultValue = "0") long after, HttpServletRequest r) {
    return result(apps.events(actor(r), id, after));
  }

  @PostMapping("/api/admin/deployments/{id}/reconcile")
  public Object reconcile(
      @PathVariable String id, @RequestBody java.util.Map<String, String> d, HttpServletRequest r) {
    return result(apps.reconcile(actor(r), id, d.get("reason")));
  }
}
