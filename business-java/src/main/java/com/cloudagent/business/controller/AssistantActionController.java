package com.cloudagent.business.controller;

import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.service.*;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** AssistantActionController：仅处理 HTTP 参数、可信身份和响应；业务规则在 Service。 */
@RestController
public class AssistantActionController {
  private final AssistantActionService actions;

  public AssistantActionController(AssistantActionService actions) {
    this.actions = actions;
  }

  @GetMapping("/api/assistant/actions")
  public Object actions(HttpServletRequest r) {
    return ApiFilter.result(actions.list(ApiFilter.actor(r)));
  }

  @PostMapping("/api/assistant/actions/preview")
  public Object preview(@Valid @RequestBody Preview d, HttpServletRequest r) {
    return ApiFilter.result(actions.preview(ApiFilter.actor(r), d));
  }

  @PostMapping("/api/assistant/actions/{id}/confirm")
  public Object confirm(
      @PathVariable String id, @Valid @RequestBody Confirm d, HttpServletRequest r) {
    return ApiFilter.result(actions.confirm(ApiFilter.actor(r), id, d.confirmed()));
  }
}
