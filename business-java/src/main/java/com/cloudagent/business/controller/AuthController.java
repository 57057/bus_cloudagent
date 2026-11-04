package com.cloudagent.business.controller;

import com.cloudagent.business.service.AuthService;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

/**
 * 认证与用户资料的 HTTP 入口，只负责取参数、设置 Cookie、调用 AuthService。 登录结果中的访问令牌交给 React 内存保存；刷新令牌只通过 HttpOnly Cookie
 * 下发。 Controller 不自己计算密码哈希，也不从请求体接受用户角色或可信用户编号。
 */
@RestController
public class AuthController {
  private final AuthService auth;
  private final boolean secure;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public AuthController(AuthService auth, @Value("${platform.secure-cookie}") boolean secure) {
    this.auth = auth;
    this.secure = secure;
  }

  /** 把刷新凭据写到 Cookie，返回 JSON 前移除 refresh_token；原始访问令牌仍用于前端 Authorization 头。 */
  private Object cookies(Map<String, Object> credentials, HttpServletResponse res) {
    res.addHeader(
        "Set-Cookie", cookie("refresh", credentials.get("refresh_token").toString(), true, 604800));
    res.addHeader(
        "Set-Cookie",
        cookie("XSRF-TOKEN", credentials.get("csrf_token").toString(), false, 604800));
    var publicData = new HashMap<>(credentials);
    publicData.remove("refresh_token");
    return ApiFilter.result(publicData);
  }

  /** 集中设置 Cookie 的生命周期、安全属性和路径；HttpOnly Cookie 不可被页面 JavaScript 读取。 */
  private String cookie(String key, String value, boolean http, int age) {
    return ResponseCookie.from(key, value)
        .httpOnly(http)
        .secure(secure)
        .sameSite("Strict")
        .path("/")
        .maxAge(age)
        .build()
        .toString();
  }

  /** 接收注册 JSON，实际规范、协议确认和密码存储逻辑在 AuthService。 */
  @PostMapping("/api/auth/register")
  public Object register(@RequestBody Map<String, Object> data) {
    return ApiFilter.result(auth.register(data));
  }

  /** 把服务器看到的来源IP交给限频逻辑；登录成功后设置刷新与CSRF Cookie。 */
  @PostMapping("/api/auth/login")
  public Object login(
      @RequestBody Map<String, Object> data, HttpServletRequest req, HttpServletResponse res) {
    return cookies(auth.login(data, req.getRemoteAddr()), res);
  }

  /** 从 Cookie 和请求头取刷新/CSRF凭据，轮换会话并重新设置 Cookie。 */
  @PostMapping("/api/auth/refresh")
  public Object refresh(
      @CookieValue(value = "refresh", defaultValue = "") String refresh,
      @RequestHeader(value = "X-CSRF-Token", defaultValue = "") String csrf,
      HttpServletResponse res) {
    return cookies(auth.refresh(refresh, csrf), res);
  }

  /** 校验退出请求并撤销会话，随后把两个 Cookie 的有效期设置为0。 */
  @PostMapping("/api/auth/logout")
  public Object logout(
      @CookieValue(value = "refresh", defaultValue = "") String refresh,
      @RequestHeader(value = "X-CSRF-Token", defaultValue = "") String csrf,
      HttpServletResponse res) {
    auth.logout(refresh, csrf);
    res.addHeader("Set-Cookie", cookie("refresh", "", true, 0));
    res.addHeader("Set-Cookie", cookie("XSRF-TOKEN", "", false, 0));
    return ApiFilter.result(null);
  }

  /** 查询当前登录用户，不接受客户端选择要查询的用户编号。 */
  @GetMapping("/api/me")
  public Object me(HttpServletRequest req) {
    return ApiFilter.result(auth.me(ApiFilter.actor(req)));
  }

  /** 修改当前用户允许编辑的资料；角色等字段不在这个入口修改。 */
  @PatchMapping("/api/me")
  public Object profile(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    return ApiFilter.result(auth.profile(ApiFilter.actor(req), data));
  }

  /** 修改密码并撤销全部登录会话，前端随后需要重新登录。 */
  @PostMapping("/api/me/password")
  public Object password(@RequestBody Map<String, Object> data, HttpServletRequest req) {
    auth.password(ApiFilter.actor(req), data);
    return ApiFilter.result(null);
  }
}
