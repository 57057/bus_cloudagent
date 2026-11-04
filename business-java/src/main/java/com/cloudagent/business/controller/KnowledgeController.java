package com.cloudagent.business.controller;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.service.*;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** KnowledgeController：仅处理 HTTP 参数、可信身份和响应；业务规则在 Service。 */
@RestController
public class KnowledgeController {
  private final KnowledgeService knowledge;
  private final InternalIdentity identity;

  public KnowledgeController(KnowledgeService knowledge, InternalIdentity identity) {
    this.knowledge = knowledge;
    this.identity = identity;
  }

  @GetMapping("/api/admin/knowledge")
  public Object documents(HttpServletRequest r) {
    return ApiFilter.result(knowledge.list(ApiFilter.actor(r)));
  }

  @GetMapping("/api/admin/knowledge/{id}")
  public Object document(@PathVariable String id, HttpServletRequest r) {
    return ApiFilter.result(knowledge.detail(ApiFilter.actor(r), id));
  }

  @PostMapping("/api/admin/knowledge")
  public Object draft(@Valid @RequestBody Document d, HttpServletRequest r) {
    return ApiFilter.result(knowledge.draft(ApiFilter.actor(r), d));
  }

  @PostMapping("/api/admin/knowledge/{id}/transition")
  public Object transition(
      @PathVariable String id, @Valid @RequestBody Decision d, HttpServletRequest r) {
    return ApiFilter.result(knowledge.transition(ApiFilter.actor(r), id, d.action(), d.reason()));
  }

  @GetMapping("/internal/knowledge/snapshot")
  public Object knowledgeSnapshot(
      @RequestHeader(value = "X-Service-Token", defaultValue = "") String token) {
    identity.service(token);
    return knowledge.snapshot();
  }
}
