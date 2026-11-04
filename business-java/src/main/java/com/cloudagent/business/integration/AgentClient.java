package com.cloudagent.business.integration;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.service.AuthService;
import com.cloudagent.business.service.InternalIdentity;
import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Python Agent 的固定上游客户端；只做协议与传输，不管理业务数据库。 */
@Component
public class AgentClient {
  private final InternalIdentity identity;
  private final JsonCodec json;
  private final String base;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public AgentClient(
      InternalIdentity identity, JsonCodec json, @Value("${agent.base-url}") String base) {
    this.identity = identity;
    this.json = json;
    this.base = base;
  }

  /** 流由调用者读取并关闭；这里只建立请求，不缓存整个模型响应。 */
  public HttpResponse<InputStream> openChat(AuthService.Actor actor, String session, String query)
      throws Exception {
    return http.send(
        request(
            "/api/chat",
            "POST",
            actor,
            session,
            Map.of("query", query, "user_id", actor.id(), "session_id", session)),
        HttpResponse.BodyHandlers.ofInputStream());
  }

  private HttpRequest request(
      String path, String method, AuthService.Actor a, String session, Object body) {
    return HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(180))
        .header("Content-Type", "application/json")
        .header("X-Service-Token", identity.token)
        .header("X-User-Context", identity.issue(a, session))
        .header("X-Request-Id", Objects.toString(MDC.get("requestId"), id()))
        .method(
            method,
            body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.json(body)))
        .build();
  }

  public Object python(String path, String method, AuthService.Actor a, String session, Object body)
      throws Exception {
    var response =
        http.send(request(path, method, a, session, body), HttpResponse.BodyHandlers.ofString());
    require(response.statusCode() == 200, 503, "AGENT_UNAVAILABLE", "Agent或记忆服务暂不可用");
    return json.parse(response.body());
  }
}
