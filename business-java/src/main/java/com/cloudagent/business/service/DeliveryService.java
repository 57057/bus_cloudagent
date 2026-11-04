package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.dto.P1Requests.*;
import com.cloudagent.business.dto.P1Responses.ContactChallenge;
import com.cloudagent.business.integration.NotificationAdapter;
import com.cloudagent.business.repository.DeliveryRepository;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 绑定/找回均需单次验证码。失败次数用显式事务先提交再抛错，避免回滚后无限猜测。通知失败不会回滚交易。 */
@Service
public class DeliveryService {
  private final DeliveryRepository r;
  private final BusinessEvents events;
  private final NotificationAdapter adapter;
  private final TransactionTemplate tx;
  private final String pepper;

  public DeliveryService(
      DeliveryRepository r,
      BusinessEvents events,
      NotificationAdapter adapter,
      PlatformTransactionManager tm,
      @org.springframework.beans.factory.annotation.Value("${service.token}") String pepper) {
    this.r = r;
    this.events = events;
    this.adapter = adapter;
    this.tx = new TransactionTemplate(tm);
    this.pepper = pepper;
  }

  public Object contact(AuthService.Actor a) {
    return r.contact(a.id());
  }

  private String normalize(Contact d) {
    require(d.channel() != null && d.destination() != null, 400, "INVALID_CONTACT", "请填写渠道和地址");
    String value = d.destination().strip();
    if (d.channel().equals("EMAIL")) {
      value = value.toLowerCase(Locale.ROOT);
      require(
          value.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
          400,
          "INVALID_EMAIL",
          "邮箱格式无效");
    } else require(value.matches("1[3-9][0-9]{9}"), 400, "INVALID_PHONE", "手机号格式无效");
    return value;
  }

  @Transactional
  public ContactChallenge requestBind(AuthService.Actor a, Contact d) {
    r.lockUser(a.id());
    limitRequests("bind:" + a.id());
    return issue(a.id(), "BIND", d, true);
  }

  @Transactional
  public ContactChallenge requestReset(Contact d, String ip) {
    limitRequests("reset:" + ip);
    String destination = normalize(d);
    var contacts = r.findContact(d.channel(), destination);
    if (!contacts.isEmpty()) r.lockUser(text(contacts.get(0), "user_id"));
    return issue(
        contacts.isEmpty() ? null : text(contacts.get(0), "user_id"),
        "RESET",
        d,
        !contacts.isEmpty());
  }

  private void limitRequests(String source) {
    String key = AuthService.hash("recovery:" + source);
    var counter = r.lockRequestLimit(key, now(), time(Instant.now().minusSeconds(600)));
    require(num(counter, "failures") < 20, 429, "CHALLENGE_LIMIT", "请求过于频繁，请10分钟后重试");
    r.countRequest(key);
  }

  private ContactChallenge issue(String user, String purpose, Contact d, boolean deliver) {
    require(!pepper.isBlank(), 503, "RECOVERY_NOT_CONFIGURED", "找回服务未配置");
    String destination = normalize(d);
    require(
        r.recent(destination, time(Instant.now().minusSeconds(600))) < 5,
        429,
        "CHALLENGE_LIMIT",
        "请求过于频繁，请10分钟后重试");
    String id = id();
    String code = String.format("%06d", new java.security.SecureRandom().nextInt(1000000));
    r.challenge(
        id,
        user,
        purpose,
        d.channel(),
        destination,
        AuthService.hmac(pepper, id + ":" + code),
        time(Instant.now().plusSeconds(600)),
        now());
    if (deliver)
      r.enqueue(
          id(),
          "challenge:" + id,
          user,
          d.channel(),
          destination,
          "验证码",
          "验证码：" + code + "；10分钟有效，请勿转发。",
          now());
    return new ContactChallenge(id, "如果该联系方式可用，验证码将发送；验证码10分钟内有效", adapter.mode());
  }

  /** 操作与消费凭据在同一事务中完成；失败返回错误文本后再抛出，保留本次猜测计数。 */
  public Object verifyBind(AuthService.Actor a, Verify d) {
    return complete(a, d.challenge_id(), d.code(), null);
  }

  public Object reset(Reset d) {
    AuthService.validatePassword(d.new_password());
    return complete(null, d.challenge_id(), d.code(), d.new_password());
  }

  private Object complete(AuthService.Actor actor, String id, String code, String password) {
    String error =
        tx.execute(
            status -> {
              Map<String, Object> c;
              try {
                var initial = r.findChallenge(id);
                if (initial.get("user_id") == null
                    || actor != null && !text(initial, "user_id").equals(actor.id()))
                  return "验证码无效或已失效";
                // 用户先于挑战加锁，避免两个找回请求在撤销该用户全部挑战时互相等待。
                r.lockUser(text(initial, "user_id"));
                c = r.lockChallenge(id);
              } catch (ApiError e) {
                return "验证码无效或已失效";
              }
              boolean valid =
                  !bool(c.get("consumed"))
                      && num(c, "attempts") < 5
                      && instant(c.get("expires_at")).isAfter(Instant.now())
                      && c.get("user_id") != null
                      && text(c, "purpose").equals(password == null ? "BIND" : "RESET")
                      && (actor == null || text(c, "user_id").equals(actor.id()));
              if (!valid) return "验证码无效或已失效";
              r.attempt(id);
              if (code == null
                  || !AuthService.equal(
                      text(c, "code_hash"), AuthService.hmac(pepper, id + ":" + code)))
                return "验证码无效或已失效";
              String user = text(c, "user_id");
              if (password == null) {
                var other = r.findContact(text(c, "channel"), text(c, "destination"));
                require(
                    other.isEmpty() || text(other.get(0), "user_id").equals(user),
                    409,
                    "CONTACT_TAKEN",
                    "联系方式不可用");
                r.bind(user, text(c, "channel"), text(c, "destination"), now());
              } else r.reset(user, new BCryptPasswordEncoder(10).encode(password));
              r.consume(id);
              events.audit(
                  user,
                  password == null ? "CONTACT_VERIFIED" : "PASSWORD_RECOVERED",
                  user,
                  "已验证单次验证码；不记录验证码和密码");
              return null;
            });
    require(error == null, 400, "INVALID_CHALLENGE", error);
    return Map.of("success", true);
  }

  public void notice(String key, String user, String title, String content) {
    var c = r.contact(user);
    if (!c.isEmpty())
      r.enqueue(
          id(),
          key,
          user,
          text(c.get(0), "channel"),
          text(c.get(0), "destination"),
          title,
          content,
          now());
  }

  public Object deliveries(AuthService.Actor a) {
    a.staffOnly();
    return r.deliveries();
  }

  public Object inbox(AuthService.Actor a) {
    a.adminOnly();
    require(adapter.mode().equals("LOCAL"), 409, "NOT_LOCAL", "外部渠道没有本地收件箱");
    events.audit(a.id(), "LOCAL_DELIVERY_INBOX", "sandbox", "管理员查看10分钟内本地模拟投递箱");
    return r.receipts(now());
  }

  @Transactional
  public void retry(AuthService.Actor a, String id, String reason) {
    a.adminOnly();
    var d = r.delivery(id);
    require(text(d, "state").equals("MANUAL_REQUIRED"), 409, "DELIVERY_STATE", "仅人工处理记录可重试");
    require(
        !text(d, "subject").equals("验证码")
            || instant(d.get("created_at")).plusSeconds(600).isAfter(Instant.now()),
        409,
        "CHALLENGE_EXPIRED",
        "验证码已过期，请重新申请");
    r.retry(id, now());
    events.audit(a.id(), "DELIVERY_RETRY", id, reason);
  }

  /** 先领取并提交租约，再执行外部投递；故障指数退避，重启后回收过期租约。外部 SMTP 不保证绝对只投递一次。 */
  public void poll() {
    for (int i = 0; i < 20; i++) {
      var d =
          tx.execute(
              st -> {
                var due = r.lockDue(now());
                if (due.isEmpty()) return null;
                var row = due.get(0);
                r.lease(text(row, "id"), time(Instant.now().plusSeconds(60)));
                return row;
              });
      if (d == null) break;
      try {
        if (text(d, "subject").equals("验证码")
            && !instant(d.get("created_at")).plusSeconds(600).isAfter(Instant.now()))
          throw new IllegalStateException("expired");
        adapter.send(d);
        tx.executeWithoutResult(st -> r.result(text(d, "id"), "SENT", now(), null));
      } catch (Exception e) {
        int count = (int) num(d, "attempts") + 1;
        tx.executeWithoutResult(
            st ->
                r.result(
                    text(d, "id"),
                    count >= 3 ? "MANUAL_REQUIRED" : "PENDING",
                    time(Instant.now().plusSeconds(1L << count)),
                    "渠道暂不可用或消息已过期"));
      }
    }
    tx.executeWithoutResult(st -> r.redactExpired(time(Instant.now().minusSeconds(600))));
  }
}
