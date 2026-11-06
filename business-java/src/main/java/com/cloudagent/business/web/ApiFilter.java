package com.cloudagent.business.web;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.common.Values;
import com.cloudagent.business.service.AuthService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP 请求进入 Controller 前的公共检查站，继承 Spring 的 OncePerRequestFilter。 执行顺序：生成请求号 → 检查浏览器来源/预检 → 判断公开接口 →
 * 校验访问令牌 → 放行。 认证成功后把 Actor 放进“当前请求”的属性，而不是放进所有请求共用的全局变量。 本过滤器不负责订单归属；资源是否属于用户，仍由具体业务服务再次验证。
 */
@Component
public class ApiFilter extends OncePerRequestFilter {
  private final JsonCodec json;

  private final AuthService auth;
  private final Set<String> allowedOrigins;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public ApiFilter(AuthService auth, JsonCodec json,
      @Value("${platform.allowed-origins}") String origins) {
    this.json = json;

    this.auth = auth;
    // 明确配置浏览器入口；不根据客户端可伪造的Host/转发头自动信任来源。
    this.allowedOrigins = new HashSet<>();
    for (String origin : origins.split(",")) {
      String value = origin.strip();
      if (value.isEmpty()) continue;
      java.net.URI uri = java.net.URI.create(value);
      if (!(Set.of("https", "http").contains(Objects.toString(uri.getScheme(), "")))
          || uri.getHost() == null || uri.getRawUserInfo() != null
          || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
          || uri.getRawQuery() != null || uri.getRawFragment() != null) {
        throw new IllegalArgumentException("Allowed origin must be an exact HTTP(S) origin");
      }
      allowedOrigins.add(value);
    }
  }

  /** 从当前 HTTP 请求属性读取过滤器认证结果；不会重新读取请求体中的用户编号。 */
  public static AuthService.Actor actor(HttpServletRequest r) {
    return (AuthService.Actor) r.getAttribute("actor");
  }

  /** 封装普通 JSON 接口响应；data 为 null 时用空对象代替，避免 Map.of 不接受 null。 */
  public static Map<String, Object> result(Object data) {
    return Map.of(
        "code",
        "OK",
        "message",
        "成功",
        "data",
        data == null ? Map.of() : data,
        "requestId",
        Objects.toString(MDC.get("requestId"), ""));
  }

  /** 执行本次请求的来源与身份校验。chain.doFilter 相当于继续调用后面的处理链；抛异常则不会继续放行。 */
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String inherited = req.getHeader("X-Request-Id");
    String requestId =
        req.getRequestURI().startsWith("/internal/")
                && inherited != null
                && inherited.matches("[a-f0-9]{32}")
            ? inherited
            : Values.id();
    // MDC是日志使用的线程上下文，不是业务用户存储；finally必须清理，避免线程复用时串到下一请求。
    MDC.put("requestId", requestId);
    res.setHeader("X-Request-Id", requestId);
    try {
      String path = req.getRequestURI(), origin = req.getHeader("Origin");
      // Origin名单用于浏览器跨站访问控制，不是服务认证；没有Origin头的非浏览器请求仍须走令牌/渠道签名校验。
      if (origin != null) {
        ApiError.require(
            allowedOrigins.contains(origin),
            403,
            "ORIGIN_DENIED",
            "来源不受信任");
        res.setHeader("Access-Control-Allow-Origin", origin);
        res.setHeader("Vary", "Origin");
        res.setHeader("Access-Control-Allow-Credentials", "true");
        res.setHeader(
            "Access-Control-Allow-Headers",
            "Content-Type,Authorization,Idempotency-Key,X-CSRF-Token");
        res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
      }
      if (req.getMethod().equals("OPTIONS")) {
        res.setStatus(204);
        return;
      }
      if (path.startsWith("/api/") && !isPublic(path, req.getMethod())) {
        String h = Objects.toString(req.getHeader("Authorization"), "");
        ApiError.require(h.startsWith("Bearer "), 401, "UNAUTHENTICATED", "请先登录");
        req.setAttribute("actor", auth.authenticate(h.substring(7)));
      }
      res.setHeader("X-Content-Type-Options", "nosniff");
      // 放行后，Spring MVC才会按URL和HTTP方法选择对应Controller方法。
      chain.doFilter(req, res);
      // 过滤器在Controller之外，认证失败在这里直接写响应，不能依赖ApiAdvice一定会捕获。
    } catch (ApiError e) {
      res.setStatus(e.status);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter()
          .write(
              json.json(
                  Map.of(
                      "code",
                      e.code,
                      "message",
                      e.getMessage(),
                      "data",
                      Map.of(),
                      "requestId",
                      requestId)));
    } catch (org.springframework.dao.DataAccessException e) {
      res.setStatus(503);
      res.setContentType("application/json;charset=UTF-8");
      res.getWriter()
          .write(
              json.json(
                  Map.of(
                      "code",
                      "DATABASE_UNAVAILABLE",
                      "message",
                      "数据库暂不可用，请稍后重试",
                      "data",
                      Map.of(),
                      "requestId",
                      requestId)));
    } finally {
      MDC.remove("requestId");
    }
  }

  /** 只豁免列出的认证入口、公开商品 GET 和渠道回调。回调虽不校验登录令牌，仍须在内部控制器验渠道签名。 */
  private boolean isPublic(String p, String method) {
    return Set.of(
                "/api/auth/register",
                "/api/auth/login",
                "/api/auth/refresh",
                "/api/auth/logout",
                "/api/auth/password-reset/request",
                "/api/auth/password-reset/confirm")
            .contains(p)
        || p.startsWith("/api/payments/callback/")
        || method.equals("GET")
            && (p.equals("/api/products")
                || p.startsWith("/api/products/")
                || p.equals("/api/regions")
                || p.equals("/api/images"));
  }
}
