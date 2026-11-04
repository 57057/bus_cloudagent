package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.entity.PlatformUser;
import com.cloudagent.business.repository.AuthRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户身份与登录会话服务。用 MyBatis-Plus 管理用户实体，用参数化 SQL 管理会话。 本项目的 access_token 是随机的不透明令牌，不是 JWT；服务器用其 SHA-256
 * 哈希查数据库。 密码使用 BCrypt；令牌使用哈希索引，两者用途不同，不能用普通 SHA-256 替代密码哈希。 Actor
 * 代表服务器认证出来的操作者；后续业务只能信任这个身份，不能信任表单中的 user_id。
 */
@Service
public class AuthService {
  private final BusinessEvents events;

  /** 不可变的操作者数据载体。record自动提供id()/role()/sessionId()访问器等方法， 例如a.id()读取用户编号；它不是数据库连接，也不是整个HTTP请求对象。 */
  public record Actor(String id, String role, String sessionId) {
    /** 判断是否属于运营或管理员；Set.of(...).contains类似Python的集合成员判断。 */
    public boolean staff() {
      return Set.of("ADMIN", "OPERATOR").contains(role);
    }

    /** 权限不满足就抛403异常，因此后面的业务代码不会继续执行。 */
    public void staffOnly() {
      require(staff(), 403, "FORBIDDEN", "需要运营或管理员权限");
    }

    /** 只允许ADMIN，运营OPERATOR也不能通过此检查。 */
    public void adminOnly() {
      require(role.equals("ADMIN"), 403, "FORBIDDEN", "需要管理员权限");
    }
  }

  private final AuthRepository repository;
  // 10是BCrypt计算成本参数，不是“只哈希10次”或“密码长度10”。
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
  // 用户不存在也执行一次BCrypt校验，减小通过明显耗时差探测用户名的机会。
  private final String dummy = bcrypt.encode(random());

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public AuthService(BusinessEvents events, AuthRepository repository) {
    this.events = events;

    this.repository = repository;
  }

  /** 用安全随机数生成32字节凭据，再转换成适合URL/请求头传输的Base64URL字符串。 */
  public static String random() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** 对随机令牌计算SHA-256摘要，数据库按摘要查找；这是不可逆摘要，不是加密。 */
  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 使用服务端密钥为文本生成HMAC-SHA256签名；只有拥有密钥的服务才能生成匹配签名。 */
  public static String hmac(String key, String text) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 调用字节摘要比较函数比较签名/摘要，避免普通字符串逐字符比较的直接早退方式；不代表整个请求都是恒定耗时。 */
  public static boolean equal(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  /** 检查最少10个Java字符、最多72个UTF-8字节，并要求字母和数字。提示文字简化写成字节，代码的下限实际按字符长度检查。 */
  public static void validatePassword(String password) {
    require(
        password.length() >= 10
            && password.getBytes(StandardCharsets.UTF_8).length <= 72
            && password.matches("(?s).*[A-Za-z].*")
            && password.matches("(?s).*\\d.*"),
        400,
        "WEAK_PASSWORD",
        "密码需10~72字节，包含字母和数字");
  }

  /** 注册必须是 USER 角色，用户插入和审计写入在同一事务内；默认不允许自动保存长期偏好。 */
  @Transactional
  public PlatformUser register(Map<String, Object> data) {
    String username = input(data, "username", 32).toLowerCase(Locale.ROOT),
        password = input(data, "password", 72);
    require(username.matches("[a-z0-9_]{3,32}"), 400, "INVALID_USERNAME", "用户名需3~32位小写字母、数字或下划线");
    validatePassword(password);
    require(
        Boolean.TRUE.equals(data.get("agreement")), 400, "AGREEMENT_REQUIRED", "请确认用户协议及模拟服务说明");
    require(repository.countUsername(username) == 0, 409, "USERNAME_TAKEN", "用户名不可用");
    PlatformUser u = new PlatformUser();
    u.id = id();
    u.username = username;
    u.passwordHash = bcrypt.encode(password);
    u.nickname = Objects.toString(data.get("nickname"), username).strip();
    require(u.nickname.length() <= 64, 400, "INVALID_INPUT", "昵称过长");
    // 不读取data中的role，即使注册请求伪造ADMIN也不能给自己提权。
    u.role = "USER";
    u.enabled = true;
    u.preferencesEnabled = false;
    u.createdAt = now();
    repository.insertUser(u);
    events.audit(u.id, "REGISTER", u.id, "用户注册并确认模拟服务协议");
    return u;
  }

  /** 按“IP+用户名”和“IP”两个维度检查15分钟失败窗口，再验证密码并创建会话。失败计数和失败审计需要保留。 */
  public Map<String, Object> login(Map<String, Object> data, String ip) {
    String username = input(data, "username", 32).toLowerCase(Locale.ROOT),
        password = input(data, "password", 100);
    String key = hash(ip + ":" + username), ipKey = hash("ip:" + ip);
    for (String k : List.of(key, ipKey)) {
      repository.initializeLoginLimit(k, now());
      repository.resetExpiredLoginLimit(now(), k, time(Instant.now().minusSeconds(900)));
      require(
          repository.loginFailures(k) < (k.equals(ipKey) ? 50 : 5),
          429,
          "LOGIN_LIMITED",
          "登录失败次数过多，请15分钟后重试");
    }
    PlatformUser u = repository.findUserByUsername(username);
    boolean matched = bcrypt.matches(password, u == null ? dummy : u.passwordHash);
    if (!matched || u == null || !Boolean.TRUE.equals(u.enabled)) {
      for (String k : List.of(key, ipKey)) repository.incrementLoginFailures(k);
      events.audit("ANONYMOUS", "LOGIN_FAILED", key, "用户名或密码不正确");
      throw new ApiError(401, "LOGIN_FAILED", "用户名或密码不正确");
    }
    repository.clearLoginFailures(key);
    events.audit(u.id, "LOGIN", u.id, "登录成功");
    // 这里是本类内部调用，不经过Spring事务代理：newSession的注解不会因此单独开启事务。
    // 当前newSession主要是一条INSERT；refresh内部调用它时，则参与refresh已经开启的事务。
    return newSession(u.id);
  }

  /** 创建15分钟访问令牌、7天刷新令牌及CSRF令牌；只把哈希保存到数据库，原值仅用于本次凭据下发。 */
  @Transactional
  public Map<String, Object> newSession(String user) {
    String sid = id(), access = random(), refresh = random(), csrf = random();
    repository.insertSession(
        sid,
        user,
        hash(access),
        hash(refresh),
        hash(csrf),
        time(Instant.now().plusSeconds(900)),
        time(Instant.now().plusSeconds(604800)));
    return credentials(user, access, refresh, csrf);
  }

  /** 组合给 Controller 的内部登录结果；Controller 会把刷新原值放入HttpOnly Cookie并从响应JSON移除。 */
  private Map<String, Object> credentials(String user, String access, String refresh, String csrf) {
    return Map.of(
        "user",
        repository.findUser(user),
        "access_token",
        access,
        "expires_in",
        900,
        "refresh_token",
        refresh,
        "csrf_token",
        csrf);
  }

  /** 锁定有效刷新会话，验证CSRF，撤销旧会话并创建新会话，避免两个刷新请求都成功重复使用旧刷新凭据。 */
  @Transactional
  public Map<String, Object> refresh(String refresh, String csrf) {
    // FOR UPDATE在refresh事务结束前持有会话行锁；并发刷新必须重新判断旧会话是否已撤销。
    var rows = repository.lockRefreshSession(hash(refresh), now());
    require(!rows.isEmpty(), 401, "UNAUTHENTICATED", "刷新会话已失效，请重新登录");
    var r = rows.get(0);
    require(equal(text(r, "csrf_hash"), hash(csrf)), 403, "CSRF_INVALID", "刷新请求校验失败");
    repository.revokeSession(r.get("id"));
    return newSession(text(r, "user_id"));
  }

  /** 每个受保护请求按访问令牌哈希联查用户和会话：同时验证访问期限、刷新总期限、撤销状态和账号启用状态。 */
  public Actor authenticate(String token) {
    var rows = repository.findAccessSession(hash(token), now(), now());
    require(!rows.isEmpty(), 401, "UNAUTHENTICATED", "请先登录或刷新会话");
    var r = rows.get(0);
    return new Actor(text(r, "id"), text(r, "role"), text(r, "session_id"));
  }

  /** 使用认证后的用户编号读取实体；passwordHash 的 JSON 输出由实体上的 @JsonIgnore 排除。 */
  public PlatformUser me(Actor a) {
    return repository.findUser(a.id());
  }

  /** 按刷新凭据定位会话并校验CSRF，然后撤销该会话；不会撤销此用户其他设备的所有会话。 */
  public void logout(String refresh, String csrf) {
    var rows = repository.findRefreshSession(hash(refresh));
    if (!rows.isEmpty()) {
      require(equal(text(rows.get(0), "csrf_hash"), hash(csrf)), 403, "CSRF_INVALID", "退出请求校验失败");
      repository.revokeRefreshSession(hash(refresh));
    }
  }

  /** 验证旧密码、保存新 BCrypt 哈希、撤销全部会话并写审计；这些本地数据库修改一起提交。 */
  @Transactional
  public void password(Actor a, Map<String, Object> data) {
    var u = repository.findUser(a.id());
    require(
        bcrypt.matches(input(data, "old_password", 100), u.passwordHash),
        400,
        "PASSWORD_MISMATCH",
        "旧密码不正确");
    String p = input(data, "new_password", 72);
    validatePassword(p);
    repository.updatePassword(bcrypt.encode(p), a.id());
    repository.revokeUserSessions(a.id());
    events.audit(a.id(), "PASSWORD_CHANGED", a.id(), "修改密码，撤销全部会话");
  }

  /** 只允许修改昵称和长期偏好开关；没有传入的字段保持原值。 */
  @Transactional
  public PlatformUser profile(Actor a, Map<String, Object> data) {
    if (data.containsKey("nickname"))
      repository.updateNickname(input(data, "nickname", 64), a.id());
    if (data.containsKey("preferences_enabled"))
      repository.updatePreferences(Boolean.TRUE.equals(data.get("preferences_enabled")), a.id());
    return me(a);
  }
}
