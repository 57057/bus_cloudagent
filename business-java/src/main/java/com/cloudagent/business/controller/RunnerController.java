package com.cloudagent.business.controller;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.web.ApiFilter.result;

import com.cloudagent.business.dto.ApplicationRequests.*;
import com.cloudagent.business.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 独立主机凭据协议，不接受用户JWT和MCP服务令牌代替Runner身份。 */
@RestController
@RequestMapping("/internal/runner")
public class RunnerController {
  private final ApplicationService apps;
  private final PoolService pool;

  public RunnerController(ApplicationService apps, PoolService pool) {
    this.apps = apps;
    this.pool = pool;
  }

  private String credential(String header) {
    require(
        header != null && header.matches("Bearer [a-f0-9]{64}"),
        401,
        "RUNNER_UNAUTHENTICATED",
        "需要Runner凭据");
    return header.substring(7);
  }

  @PostMapping("/register")
  public Object register(@Valid @RequestBody Register d) {
    return result(pool.register(d));
  }

  @PostMapping("/tasks/claim")
  public Object claim(@RequestHeader(value = "Authorization", required = false) String h) {
    return result(apps.claim(credential(h)));
  }

  @PostMapping("/heartbeat")
  public Object heartbeat(@RequestHeader(value = "Authorization", required = false) String h) {
    return result(apps.heartbeat(credential(h)));
  }

  @PostMapping("/capabilities")
  public Object capabilities(
      @RequestHeader(value = "Authorization", required = false) String h,
      @RequestBody java.util.Map<String, Object> data) {
    return result(pool.capabilities(credential(h), data));
  }

  @PostMapping("/tasks/{id}/events")
  public Object event(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String h,
      @Valid @RequestBody Event d) {
    return result(apps.event(credential(h), id, d));
  }

  @PostMapping("/tasks/{id}/control")
  public Object control(@PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String h,
      @Valid @RequestBody Checkpoint d) {
    return result(apps.control(credential(h), id, d));
  }

  @PostMapping("/tasks/{id}/commit")
  public Object commit(@PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String h,
      @Valid @RequestBody Checkpoint d) {
    return result(apps.beginCommit(credential(h), id, d));
  }

  @PostMapping("/tasks/{id}/complete")
  public Object complete(
      @PathVariable String id,
      @RequestHeader(value = "Authorization", required = false) String h,
      @Valid @RequestBody Completion d) {
    return result(apps.complete(credential(h), id, d));
  }
}
