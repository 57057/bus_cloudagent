package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.dto.P1Requests.Provider;
import com.cloudagent.business.integration.*;
import com.cloudagent.business.repository.ProviderRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 外部接入配置与对账。凭据不入库不回显；支付宝只允许沙箱，阿里云只开放无创建的预检。 */
@Service
public class ProviderService {
  private final ProviderRepository r;
  private final JsonCodec json;
  private final BusinessEvents events;
  private final AlipaySandboxClient alipay;
  private final AliyunClient aliyun;
  private final NotificationAdapter notifications;

  public ProviderService(
      ProviderRepository r,
      JsonCodec json,
      BusinessEvents events,
      AlipaySandboxClient alipay,
      AliyunClient aliyun,
      NotificationAdapter notifications) {
    this.r = r;
    this.json = json;
    this.events = events;
    this.alipay = alipay;
    this.aliyun = aliyun;
    this.notifications = notifications;
  }

  public boolean enabled(String provider, String mode) {
    var rows = r.config(provider);
    return !rows.isEmpty() && text(rows.get(0), "mode").equals(mode);
  }

  public Object list(AuthService.Actor a) {
    a.adminOnly();
    return Map.of(
        "configs",
        r.configs(),
        "alipay_credentials_ready",
        alipay.configured(),
        "alipay_configuration",
        alipay.configurationStatus(),
        "aliyun_credentials_ready",
        aliyun.configured(),
        "alipay_gateway",
        AlipaySandboxClient.GATEWAY,
        "real_resource_creation_enabled",
        false,
        "actual_delivery_mode",
        notifications.mode());
  }

  @Transactional
  public Object save(AuthService.Actor a, Provider d) {
    a.adminOnly();
    Set<String> keys =
        Set.of("RegionId", "ZoneId", "InstanceType", "ImageId", "SecurityGroupId", "VSwitchId");
    require(
        d.settings().keySet().stream().allMatch(keys::contains)
            && d.settings().values().stream().allMatch(v -> v != null && v.length() <= 150),
        400,
        "SECRET_NOT_ALLOWED",
        "仅允许非秘密的阿里云资源映射；密钥放本地环境变量");
    require(
        d.mode().equals("DISABLED")
            || d.provider().equals("ALIPAY_SANDBOX") && d.mode().equals("SANDBOX")
            || d.provider().equals("ALIYUN_ECS") && d.mode().equals("DRY_RUN")
            || d.provider().equals("DELIVERY") && d.mode().equals("LOCAL"),
        400,
        "MODE_NOT_ALLOWED",
        "只支持支付宝沙箱、阿里云预检或本地投递");
    r.save(d.provider(), d.mode(), json.json(d.settings()), now());
    events.audit(
        a.id(), "PROVIDER_CONFIG", d.provider(), "模式：" + d.mode() + "；原因：" + d.reason() + "；不记录凭据");
    return list(a);
  }

  public Object dryRun(AuthService.Actor a) {
    a.adminOnly();
    require(enabled("ALIYUN_ECS", "DRY_RUN"), 409, "PROVIDER_DISABLED", "请先配置阿里云预检");
    var cfg = json.parse(r.config("ALIYUN_ECS").get(0).get("settings"));
    Map<String, String> values = new HashMap<>();
    cfg.forEach((k, v) -> values.put(k, v.toString()));
    Object result = aliyun.dryRun(values, id());
    events.audit(a.id(), "ALIYUN_DRY_RUN", "ECS", "仅预检，不创建云资源");
    return result;
  }

  /** 订单锁保证同一订单不同时创建模拟与沙箱支付单；沙箱网络请求在订单事务提交后执行。 */
  @Transactional
  public Object prepare(AuthService.Actor a, String order) {
    require(
        enabled("ALIPAY_SANDBOX", "SANDBOX") && alipay.configured(),
        503,
        "PROVIDER_NOT_CONFIGURED",
        "支付宝沙箱未启用或凭据不完整");
    var o = r.lockOrder(order, a.id());
    require(
        text(o, "state").equals("PENDING_PAYMENT")
            && instant(o.get("payment_deadline")).isAfter(java.time.Instant.now()),
        409,
        "ORDER_STATE_CONFLICT",
        "订单不可付款");
    var old = r.orderPayment(order);
    if (!old.isEmpty()) {
      require(
          text(old.get(0), "channel").equals("ALIPAY_SANDBOX"),
          409,
          "PAYMENT_CHANNEL_CONFLICT",
          "已有其他渠道支付单，请取消订单后重新下单");
      return old.get(0);
    }
    String id = id();
    r.insertPayment(id, order, a.id(), o.get("total_cents"), now());
    events.audit(a.id(), "ALIPAY_SANDBOX_PAYMENT", id, "创建沙箱支付单");
    return r.payment(id);
  }

  public Object qr(AuthService.Actor a, String id) {
    var p = r.payment(id);
    require(
        text(p, "user_id").equals(a.id()) && text(p, "channel").equals("ALIPAY_SANDBOX"),
        404,
        "NOT_FOUND",
        "支付单不存在");
    require(enabled("ALIPAY_SANDBOX", "SANDBOX"), 409, "PROVIDER_DISABLED", "沙箱渠道已禁用");
    var old = r.qr(id);
    String qr =
        old.isEmpty() ? alipay.create(id, num(p, "amount_cents")) : text(old.get(0), "qr_code");
    r.qr(id, qr);
    return Map.of("payment_id", id, "qr_code", qr, "mode", "ALIPAY_SANDBOX");
  }

  /** 服务端轮询权威渠道，结果台账交给既有付款状态机；回调是加速信号而非直接相信浏览器。 */
  public Map<String, Object> queryAlipay(String id) {
    require(enabled("ALIPAY_SANDBOX", "SANDBOX"), 409, "PROVIDER_DISABLED", "沙箱渠道已禁用");
    var p = r.payment(id);
    require(text(p, "channel").equals("ALIPAY_SANDBOX"), 409, "PAYMENT_CHANNEL", "不是沙箱支付单");
    var result = alipay.query(id, num(p, "amount_cents"));
    r.providerPayment(
        id,
        text(result, "transaction_id"),
        num(result, "amount_cents"),
        text(result, "state"),
        now());
    return result;
  }

  public void reconcile() {
    if (!enabled("ALIPAY_SANDBOX", "SANDBOX") || !alipay.configured()) return;
    for (var p : r.pendingAlipay())
      try {
        queryAlipay(text(p, "id"));
      } catch (Exception ignored) {
        /* 保持未确认状态，后续主动查询；不把异常当成功。 */
      }
  }

  public void callback(Map<String, String> values) {
    require(enabled("ALIPAY_SANDBOX", "SANDBOX"), 409, "PROVIDER_DISABLED", "沙箱未启用");
    alipay.verifyCallback(values);
    String id = values.get("out_trade_no");
    require(id != null, 400, "INVALID_CALLBACK", "缺少支付号");
    queryAlipay(id);
  }
}
