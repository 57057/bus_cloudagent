package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.integration.AgentClient;
import com.cloudagent.business.repository.ChatRepository;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.MDC;

/**
 * 前端与 Python Agent 之间的 Java 聊天网关，以及本人会话/偏好管理入口。 Java 负责登录认证、会话归属、可信用户上下文和消息存档；Python 负责模型与 Agent
 * 编排。 HTTP 返回的是 SSE 流：网关持续读取 Python 的事件并写给浏览器，而不是一次性 JSON。 业务健康检查只依赖本服务和
 * MySQL，Python/模型故障不应阻断普通交易页面。
 */
@org.springframework.stereotype.Service
public class ChatService {
  private final JsonCodec json;

  /** 与 HTTP 框架无关的输出流回调；Controller 决定 SSE 响应头。 */
  @FunctionalInterface
  public interface ChatStream {
    void writeTo(OutputStream output) throws IOException;
  }

  private final ChatRepository repository;
  private final TradeService trade;
  private final AgentClient client;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public ChatService(
      JsonCodec json, ChatRepository repository, TradeService trade, AgentClient client) {
    this.json = json;

    this.repository = repository;
    this.trade = trade;
    this.client = client;
  }

  /** 只检测Java/MySQL基础健康，不把Python或大模型当成业务服务健康检查的必需依赖。 */
  public Object health() {
    return Map.of(
        "status",
        "ok",
        "business_database",
        repository.databasePing() == 1,
        "mode",
        "SIMULATED",
        "agent_independent",
        true);
  }

  /** 由服务端生成聊天会话ID并绑定认证用户，避免前端任意指定他人的会话。 */
  public Object create(Map<String, Object> d, AuthService.Actor a) {
    String id = id(), title = Objects.toString(d.get("title"), "新会话");
    require(title.length() <= 100, 400, "INVALID_INPUT", "标题过长");
    repository.insertSession(id, a.id(), title, now());
    return repository.findSession(id);
  }

  /** 查询本人的未删除会话，最多100条；不是把所有用户会话交给前端再过滤。 */
  public Object sessions(AuthService.Actor a) {
    return repository.listUserSessions(a.id());
  }

  /** 复用归属校验并拒绝软删除会话；后续消息读取、聊天和删除都先经过这里。 */
  private Map<String, Object> session(AuthService.Actor a, String id) {
    var session = trade.owned("chat_sessions", id, a.id(), false);
    require(!bool(session.get("deleted")), 404, "NOT_FOUND", "会话已删除");
    return session;
  }

  /** 验证会话归属后按时间和ID读取已持久化消息。 */
  public Object history(String id, AuthService.Actor a) {
    session(a, id);
    return repository.listMessages(id);
  }

  /** 软删除会话并清除Java消息，再尽力通知Python清理；即使Python不可用，已删除ID也不能再用于聊天，旧Redis记录按TTL失效。 */
  public Object delete(String id, AuthService.Actor a) {
    session(a, id);
    repository.markSessionDeleted(id, a.id());
    repository.deleteMessages(id);
    try {
      client.python("/api/internal/memory/session/" + id, "DELETE", a, id, null);
    } catch (Exception ignored) {
      /* Deleted IDs cannot be reused; old Redis entries expire by TTL. */
    }
    return null;
  }

  /** 通过可信上下文查看本人的长期偏好，Java不直接读取Milvus。 */
  public Object preferences(AuthService.Actor a) throws Exception {
    return client.python("/api/internal/memory/preferences", "GET", a, "preferences", null);
  }

  /** 转发本人偏好删除请求，具体用户过滤仍由Python内部接口执行。 */
  public Object preference(String id, AuthService.Actor a) throws Exception {
    return client.python(
        "/api/internal/memory/preferences/" + id, "DELETE", a, "preferences", null);
  }

  /** 先认证用户和会话，再请求Python。回调体逐行转发SSE，同时收集文本用于存档；上游异常只影响AI入口。 */
  public ChatStream chat(Map<String, Object> d, AuthService.Actor a) {
    String session = input(d, "session_id", 64), query = input(d, "query", 10000);
    session(a, session);
    // 先把线程日志上下文中的请求号复制为局部变量，流式回调不应假定仍运行在原请求线程。
    String requestId = Objects.toString(MDC.get("requestId"), id());
    HttpResponse<InputStream> upstream;
    try {
      upstream = client.openChat(a, session, query);
    } catch (Exception e) {
      throw new ApiError(503, "AGENT_UNAVAILABLE", "AI服务暂不可用，交易及资源页面仍可使用");
    }
    if (upstream.statusCode() != 200) {
      try {
        upstream.body().close();
      } catch (IOException ignored) {
      }
      throw new ApiError(503, "AGENT_UNAVAILABLE", "AI服务暂不可用，请稍后重试");
    }
    // 确认上游已建立成功响应后再保存用户消息；随后由StreamingResponseBody回调处理响应流。
    repository.insertUserMessage(id(), session, "user", query, now());
    // 这个lambda不是立刻执行模型推理，而是交给Spring在写HTTP响应体时调用。
    // out是发给浏览器的输出流；reader读取的则是Python发回来的输入流。
    ChatStream stream =
        out -> {
          StringBuilder answer = new StringBuilder();
          boolean done = false;
          try (var reader =
              new BufferedReader(new InputStreamReader(upstream.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (line.startsWith("data: ")) {
                try {
                  var event = json.parse(line.substring(6));
                  if (event.get("content") instanceof String content) answer.append(content);
                  if (Boolean.TRUE.equals(event.get("done"))) done = true;
                } catch (Exception ignored) {
                }
              }
              // 逐行保留SSE格式，空行也要转发，因为SSE用空行分隔事件；flush减少缓冲等待。
              out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
            if (!done) {
              out.write(
                  ("event: error\ndata: "
                          + json.json(Map.of("error", "AI响应中断", "requestId", requestId))
                          + "\n\n")
                      .getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
            // 即使断流，也保存已接收到的非空文本片段；因此“有assistant存档”不必然代表收到完整done。
          } finally {
            if (answer.length() > 0 && repository.countActiveSessions(session) == 1)
              repository.insertAssistantMessage(
                  id(), session, "assistant", answer.toString(), now());
          }
        };
    return stream;
  }
}
