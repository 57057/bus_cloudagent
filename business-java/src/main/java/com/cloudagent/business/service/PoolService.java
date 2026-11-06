package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.*;
import com.cloudagent.business.dto.ApplicationRequests.*;
import com.cloudagent.business.repository.PoolRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台资产由管理员纳管；用户只获得独占租约，不获得云账号或Runner凭据。 */
@Service
public class PoolService {
  @org.springframework.beans.factory.annotation.Value("${platform.shared-demo:false}")
  private boolean sharedDemoEnabled;
  @Transactional public Object adoptWindows(AuthService.Actor a,String id){
    a.adminOnly();var h=r.host(id);require(text(h,"mode").equals("ALIYUN") && text(h,"pool_state").equals("PREPARING"),409,"HOST_STATE","只允许待纳管的阿里云资产登记Windows静态站点能力");
    var verified=aliyun.windowsAsset(text(r.skuInfo(text(h,"sku_id")),"region"),text(h,"provider_instance_id"));
    r.adoptWindows(id,json.json(Map.of("execution_profile","WINDOWS_STATIC_IIS","transport","ALIYUN_CLOUD_ASSISTANT","docker_ready",false,"static_site",true,"cloud_identity_verified",verified)));
    r.refreshCapacity(text(h,"sku_id"));events.audit(a.id(),"WINDOWS_STATIC_HOST_ADOPTED",id,"Windows云助手静态站点纳管；不进入Linux容器可售池");
    return Map.of("id",id,"state","MANAGED_STATIC","profile","WINDOWS_STATIC_IIS","rentable_as_linux",false);
  }
  @org.springframework.beans.factory.annotation.Autowired
  private com.cloudagent.business.integration.AliyunClient aliyun;

  private final PoolRepository r;
  private final JsonCodec json;
  private final BusinessEvents events;

  public PoolService(PoolRepository r, JsonCodec json, BusinessEvents events) {
    this.r = r;
    this.json = json;
    this.events = events;
  }

  public Object hosts(AuthService.Actor a) {
    a.adminOnly();
    return r.hosts();
  }

  public Object executions(AuthService.Actor a, String host) {
    a.adminOnly();
    return r.executions(host);
  }

  @Transactional
  public Object retryCleanup(AuthService.Actor a, String id, String reason) {
    a.adminOnly();
    require(
        reason != null && reason.length() >= 8 && reason.length() <= 500,
        400,
        "REASON_REQUIRED",
        "填写确认清理影响和处置原因");
    var task = r.execution(id);
    var h = r.host(text(task, "host_id"));
    require(
        text(task, "kind").equals("CLEANUP")
            && text(task, "state").equals("FAILED")
            && text(h, "pool_state").equals("QUARANTINED")
            && num(task, "generation") == num(h, "generation"),
        409,
        "CLEANUP_STATE",
        "仅可重试同代次隔离实例的已失败清理任务");
    r.retryCleanup(h, id);
    events.audit(a.id(), "POOL_CLEANUP_RETRY", id, reason);
    return Map.of("queued", true);
  }

  public Object allocations(AuthService.Actor a) {
    return r.allocations(a.id());
  }

  @Transactional
  public Object finance(AuthService.Actor a, String id, Finance d) {
    a.adminOnly();
    r.host(id);
    Instant until = Instant.parse(d.procurementExpiresAt());
    require(
        d.monthlyCostCents() != null && d.monthlyCostCents() >= 0,
        400,
        "INVALID_COST",
        "成本以非负整数分记录");
    require(
        until.isAfter(Instant.now()) && r.leasesBeyond(id, time(until)) == 0,
        409,
        "LEASE_COVERAGE",
        "采购期限不能短于当前有效租约");
    r.finance(id, d.monthlyCostCents(), time(until));
    events.audit(a.id(), "POOL_FINANCE_UPDATED", id, "采购资料更新；原因：" + d.reason());
    return Map.of("updated", true);
  }

  public Object financeReport(AuthService.Actor a) {
    a.adminOnly();
    return r.financeReport();
  }

  public Object discover(AuthService.Actor a, String region) {
    a.adminOnly();
    require(
        region != null && region.matches("[a-z]{2}-[a-z0-9-]{2,40}"),
        400,
        "INVALID_REGION",
        "地域格式错误");
    return aliyun.inventory(region);
  }

  @Transactional
  public Object revoke(AuthService.Actor a, String id) {
    a.adminOnly();
    var h = r.host(id);
    require(
        Set.of("PREPARING", "IDLE", "QUARANTINED").contains(text(h, "pool_state"))
            && r.running(id) == 0,
        409,
        "HOST_BUSY",
        "在租、清理或执行中的实例不能撤销");
    r.revoke(id);
    r.refreshCapacity(text(h, "sku_id"));
    events.audit(a.id(), "POOL_HOST_REVOKED", id, "仅撤销纳管凭据；未销毁云实例");
    return Map.of("revoked", true);
  }

  @Transactional
  public Object add(AuthService.Actor a, Host d) {
    a.adminOnly();
    require(d.mode() != null, 400, "INVALID_INPUT", "需要明确本地Linux或阿里云资源模式");
    if ("SHARED_DEMO".equals(d.mode()))
      require(sharedDemoEnabled, 409, "SHARED_DEMO_DISABLED", "共享控制面模式仅允许显式启用的受限演示环境");
    require(
        text(r.sku(d.skuId()), "adapter").equals("POOL"), 400, "POOL_SKU_REQUIRED", "先创建POOL类型SKU");
    Instant until = Instant.parse(d.procurementExpiresAt());
    require(until.isAfter(Instant.now()), 400, "INVALID_EXPIRY", "采购有效期必须在未来");
    require(
        d.endpoint().matches("[A-Za-z0-9.:-]{1,64}"),
        400,
        "INVALID_ENDPOINT",
        "入口仅填写64字符以内的主机名/IP及端口，不包含URL或凭据");
    String id = id();
    r.insertHost(
        id, d.providerId(), d.skuId(), d.name(), d.endpoint(), d.mode(), time(until), now());
    events.audit(a.id(), "POOL_HOST_ADDED", id, "SHARED_DEMO".equals(d.mode())
        ? "同机演示应用槽；期限仅用于演示租约，不表示云预付款或独占ECS；尚未通过Runner检测"
        : "平台实例纳管；尚未通过Runner检测");
    return Map.of("id", id);
  }

  @Transactional
  public Object token(AuthService.Actor a, String host) {
    a.adminOnly();
    var h = r.host(host);
    require(text(h, "pool_state").equals("PREPARING"), 409, "HOST_STATE", "只允许未纳管实例注册");
    String token = id() + id();
    r.token(AuthService.hash(token), host, time(Instant.now().plusSeconds(600)));
    return Map.of("token", token, "host_id", host, "expires_in", 600);
  }

  @Transactional
  public Object register(Register d) {
    var t = r.token(AuthService.hash(d.token()));
    var h = r.host(text(t, "host_id"));
    require(
        t.get("consumed_at") == null && instant(t.get("expires_at")).isAfter(Instant.now()),
        401,
        "REGISTRATION_EXPIRED",
        "注册凭据无效或已使用");
    require(text(h, "pool_state").equals("PREPARING"), 409, "HOST_STATE", "主机已经注册");
    require(
        Boolean.TRUE.equals(d.capabilities().get("docker_ready"))
            && "x86_64".equals(d.capabilities().get("architecture")),
        400,
        "HOST_CAPABILITIES",
        "需要可用Docker和x86_64主机");
    String credential = id() + id();
    r.consume(AuthService.hash(d.token()));
    // 只接收能力白名单；不把客户端提供的任意正文存入平台公开数据。
    Map<String, Object> capabilities = new TreeMap<>();
    capabilities.put("docker_ready", true);
    capabilities.put("architecture", "x86_64");
    capabilities.put("execution_protocol", "2".equals(Objects.toString(d.capabilities().get("execution_protocol"), "")) ? 2 : 1);
    if ("SHARED_DEMO".equals(text(h, "mode"))) {
      capabilities.put("shared_control_plane", true);
      capabilities.put("rentable_as_dedicated", false);
    }
    for (String field : List.of("backend_digest", "frontend_digest")) {
      String digest = Objects.toString(d.capabilities().get(field), "");
      require(
          digest.matches("sha256:[a-f0-9]{64}"),
          400,
          "IMAGE_DIGEST_REQUIRED",
          "Runner需要报告平台批准镜像的不可变摘要");
      capabilities.put(field, digest);
    }
    r.register(text(h, "id"), AuthService.hash(credential), json.json(capabilities));
    r.refreshCapacity(text(h, "sku_id"));
    return Map.of("host_id", h.get("id"), "credential", credential);
  }

  /** 调用方已锁SKU；在订单事务中绑定逐台item，任一步失败全部回滚。 */
  public void reserve(String order) {
    var items = r.orderItems(order);
    if (items.isEmpty() || !r.pooledSku(text(items.get(0), "sku_id"))) return;
    Instant until =
        Instant.now()
            .atZone(ZoneOffset.UTC)
            .plusMonths(num(items.get(0), "months"))
            .plusDays(num(r.sku(text(items.get(0), "sku_id")), "grace_days") + 1)
            .toInstant();
    var hosts = r.idle(text(items.get(0), "sku_id"), time(until));
    require(hosts.size() >= items.size(), 409, "POOL_INSUFFICIENT", "在线且采购租期足够的实例不足");
    for (int i = 0; i < items.size(); i++)
      r.reserve(text(hosts.get(i), "id"), text(items.get(i), "id"));
  }

  public void cancel(String order) {
    r.cancel(order);
  }

  @Transactional
  public Object capabilities(String credential, Map<String, Object> data) {
    var h = r.runner(AuthService.hash(credential));
    Map<String, Object> values = new TreeMap<>();
    values.put("docker_ready", true);
    values.put("architecture", "x86_64");
    values.put("execution_protocol", "2".equals(Objects.toString(data.get("execution_protocol"), "")) ? 2 : 1);
    if ("SHARED_DEMO".equals(text(h, "mode"))) {
      values.put("shared_control_plane", true);
      values.put("rentable_as_dedicated", false);
    }
    for (String name : List.of("backend_digest", "frontend_digest")) {
      String digest = Objects.toString(data.get(name), "");
      require(digest.matches("sha256:[a-f0-9]{64}"), 400, "IMAGE_DIGEST_REQUIRED", "需要不可变镜像摘要");
      values.put(name, digest);
    }
    r.capabilities(text(h, "id"), json.json(values));
    return Map.of("accepted", true);
  }

  public boolean supports(String instance) {
    return !r.allocation(instance).isEmpty() || !r.reservation(instance).isEmpty();
  }

  public Map<String, Object> provision(String instance, Instant expires) {
    var old = r.allocation(instance);
    if (old.isEmpty()) {
      var rows = r.reservation(instance);
      require(!rows.isEmpty(), 409, "POOL_RESERVATION_MISSING", "实例预留不存在");
      var h = rows.get(0);
      require(
          instant(h.get("procurement_expires_at")).isAfter(expires),
          409,
          "PROCUREMENT_EXPIRY",
          "采购有效期不足");
      r.allocate(id(), h, instance, time(expires), now());
    }
    var result = query(instance);
    r.instanceMetadata(instance, text(result, "endpoint"));
    return result;
  }

  public Map<String, Object> query(String instance) {
    var a = r.allocation(instance).get(0);
    var result = new LinkedHashMap<>(a);
    // 旧业务表provider_id唯一标识一次租用交付，不能重复使用可多次出租的物理资产编号。
    result.put("provider_id", "pool-" + a.get("id"));
    result.put("updated_at", now());
    return result;
  }

  public void renew(String instance, Instant expires) {
    var a = query(instance);
    long grace = num(r.skuInfo(text(r.instance(instance), "sku_id")), "grace_days");
    require(
        instant(a.get("procurement_expires_at")).isAfter(expires.plus(Duration.ofDays(grace))),
        409,
        "PROCUREMENT_EXPIRY",
        "平台采购期限无法覆盖续费");
    r.extend(instance, time(expires));
    r.freeze(instance, "ACTIVE");
  }

  /** 外部任务异步执行，调用方遇到PENDING只延期重试，不消耗失败次数。 */
  public void state(String instance, String target) {
    var a = query(instance);
    var h = r.host(text(a, "host_id"));
    var local = r.instance(instance);
    String kind =
        target.equals("RELEASED") ? "CLEANUP" : target.equals("RUNNING") ? "RESUME" : "PAUSE";
    String key =
        AuthService.hash(
            instance
                + ":"
                + kind
                + ":"
                + local.get("op_version")
                + ":"
                + (kind.equals("PAUSE") ? local.get("expires_at") : ""));
    if (r.command(key).isEmpty())
      require(r.running(text(h, "id")) == 0, 409, "POOL_ACTION_PENDING", "等待在途部署或人工对账结束");
    if (kind.equals("CLEANUP")) {
      r.reclaim(instance);
      h = r.host(text(a, "host_id"));
    }
    r.command(
        key, a, h, kind, json.json(Map.of("instance_id", instance, "allocation_id", a.get("id"))));
    var task = r.command(key).get(0);
    require(
        !Set.of("FAILED", "MANUAL_REQUIRED").contains(text(task, "state")),
        409,
        "POOL_ACTION_FAILED",
        "Runner执行失败，请检查部署事件");
    require(text(task, "state").equals("SUCCEEDED"), 409, "POOL_ACTION_PENDING", "等待Runner完成资源操作");
    if (!kind.equals("CLEANUP")) r.freeze(instance, target.equals("RUNNING") ? "ACTIVE" : "FROZEN");
  }
}
