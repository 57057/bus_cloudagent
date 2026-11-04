package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.repository.IdentityRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Java 给 Python/MCP 签发并验证的短期委托身份。 服务令牌证明“调用方是内部服务”；签名上下文再说明“这次代表哪个已登录用户”。 上下文包含
 * user_id、登录会话、聊天会话、过期时间、偏好开关和请求号。 Base64URL 只是编码，不是加密；HMAC 防止内容被篡改，数据库会话检查支持退出后撤销。
 */
@Component
public class InternalIdentity {
  private final JsonCodec json;

  private final IdentityRepository repository;
  public final String token;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public InternalIdentity(
      JsonCodec json, IdentityRepository repository, @Value("${service.token}") String token) {
    this.json = json;

    this.repository = repository;
    this.token = token;
  }

  /** 比较调用方服务令牌；失败即抛401。只有这一步成功仍不足以代表某个具体用户。 */
  public void service(String incoming) {
    require(
        !token.isBlank() && AuthService.equal(token, incoming),
        401,
        "SERVICE_UNAUTHENTICATED",
        "内部服务认证失败");
  }

  /** 把认证用户及会话信息序列化为JSON、Base64URL编码并签名；5分钟有效，不把签名上下文当作加密数据。 */
  public String issue(AuthService.Actor a, String session) {
    String payload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                json.json(
                        Map.of(
                            "user_id",
                            a.id(),
                            "session_id",
                            session,
                            "auth_session",
                            a.sessionId(),
                            "exp",
                            Instant.now().getEpochSecond() + 300,
                            "request_id",
                            Objects.toString(org.slf4j.MDC.get("requestId"), id()),
                            "preferences_enabled",
                            bool(repository.findPreferences(a.id()).get("preferences_enabled"))))
                    .getBytes(StandardCharsets.UTF_8));
    // 接收方分别验证payload和签名。知道payload内容并不能在没有密钥时修改其中的user_id。
    return payload + "." + AuthService.hmac(token, payload);
  }

  /** 验证签名、结构、过期时间，再联查真实登录会话是否撤销、是否过期、用户是否停用。返回的user_id才可用于工具查询。 */
  public Map<String, Object> verify(String context) {
    String[] parts = context.split("\\.");
    require(
        parts.length == 2
            && !token.isBlank()
            && AuthService.equal(AuthService.hmac(token, parts[0]), parts[1]),
        401,
        "CONTEXT_INVALID",
        "用户上下文签名无效");
    Map<String, Object> p;
    try {
      p = json.parse(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new ApiError(401, "CONTEXT_INVALID", "用户上下文无效");
    }
    require(num(p, "exp") > Instant.now().getEpochSecond(), 401, "CONTEXT_EXPIRED", "用户上下文已过期");
    require(
        repository.countActiveDelegationSessions(p.get("auth_session"), p.get("user_id"), now())
            == 1,
        401,
        "CONTEXT_REVOKED",
        "用户会话已撤销");
    return p;
  }
}
