package com.cloudagent.business.controller;

import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.service.ChatService;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 前端与 Python Agent 之间的 Java 聊天网关，以及本人会话/偏好管理入口。 Java 负责登录认证、会话归属、可信用户上下文和消息存档；Python 负责模型与 Agent
 * 编排。 HTTP 返回的是 SSE 流：网关持续读取 Python 的事件并写给浏览器，而不是一次性 JSON。 业务健康检查只依赖本服务和
 * MySQL，Python/模型故障不应阻断普通交易页面。
 */
@RestController
public class ChatController {
  private final ChatService service;

  public ChatController(ChatService service) {
    this.service = service;
  }

  /** 只检测Java/MySQL基础健康，不把Python或大模型当成业务服务健康检查的必需依赖。 */
  @GetMapping("/health")
  public Object health() {
    return service.health();
  }

  /** 由服务端生成聊天会话ID并绑定认证用户，避免前端任意指定他人的会话。 */
  @PostMapping("/api/chat/sessions")
  public Object create(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ApiFilter.result(service.create(d, ApiFilter.actor(r)));
  }

  /** 查询本人的未删除会话，最多100条；不是把所有用户会话交给前端再过滤。 */
  @GetMapping("/api/chat/sessions")
  public Object sessions(HttpServletRequest r) {
    return ApiFilter.result(service.sessions(ApiFilter.actor(r)));
  }

  /** 验证会话归属后按时间和ID读取已持久化消息。 */
  @GetMapping("/api/chat/sessions/{id}")
  public Object history(@PathVariable String id, HttpServletRequest r) {
    return ApiFilter.result(service.history(id, ApiFilter.actor(r)));
  }

  /** 软删除会话并清除Java消息，再尽力通知Python清理；即使Python不可用，已删除ID也不能再用于聊天，旧Redis记录按TTL失效。 */
  @DeleteMapping("/api/chat/sessions/{id}")
  public Object delete(@PathVariable String id, HttpServletRequest r) {
    return ApiFilter.result(service.delete(id, ApiFilter.actor(r)));
  }

  /** 通过可信上下文查看本人的长期偏好，Java不直接读取Milvus。 */
  @GetMapping("/api/me/preferences")
  public Object preferences(HttpServletRequest r) throws Exception {
    return ApiFilter.result(service.preferences(ApiFilter.actor(r)));
  }

  /** 转发本人偏好删除请求，具体用户过滤仍由Python内部接口执行。 */
  @DeleteMapping("/api/me/preferences/{id}")
  public Object preference(@PathVariable String id, HttpServletRequest r) throws Exception {
    return ApiFilter.result(service.preference(id, ApiFilter.actor(r)));
  }

  /** 先认证用户和会话，再请求Python。回调体逐行转发SSE，同时收集文本用于存档；上游异常只影响AI入口。 */
  @PostMapping("/api/chat")
  public ResponseEntity<StreamingResponseBody> chat(
      @RequestBody Map<String, Object> d, HttpServletRequest r) {
    var stream = service.chat(d, ApiFilter.actor(r));
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        .header("Cache-Control", "no-cache")
        .header("X-Accel-Buffering", "no")
        .body(stream::writeTo);
  }
}
