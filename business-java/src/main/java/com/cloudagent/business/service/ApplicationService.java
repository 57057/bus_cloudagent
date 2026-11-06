package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.*;
import com.cloudagent.business.dto.ApplicationRequests.*;
import com.cloudagent.business.integration.ApplicationSecretVault;
import com.cloudagent.business.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 计划是执行意图而非执行结果；所有入口重新验证当前租约代次。 */
@Service
public class ApplicationService {
  @org.springframework.beans.factory.annotation.Autowired private ApplicationSecretVault vault;
  private final ApplicationRepository r;
  private final PoolRepository pool;
  private final JsonCodec json;
  private final BusinessEvents events;
  private final String backendImage, frontendImage;

  public ApplicationService(
      ApplicationRepository r,
      PoolRepository pool,
      JsonCodec json,
      BusinessEvents events,
      @Value("${platform.application-backend-image:cloudagent-demo-backend:1}") String backend,
      @Value("${platform.application-frontend-image:cloudagent-demo-frontend:1}") String frontend) {
    this.r = r;
    this.pool = pool;
    this.json = json;
    this.events = events;
    this.backendImage = backend;
    this.frontendImage = frontend;
  }

  public Object projects(AuthService.Actor a) {
    return r.projects(a.id());
  }

  public Object plans(AuthService.Actor a) {
    return r.plans(a.id());
  }

  @Transactional
  public Object config(AuthService.Actor a, String project) {
    var p = r.project(project, a.id());
    r.allocation(text(p, "allocation_id"), a.id());
    var rows = r.config(project);
    if (rows.isEmpty())
      return Map.of("version", 0, "variables", Map.of(), "secret_names", List.of());
    var c = rows.get(0);
    return Map.of(
        "version",
        c.get("version"),
        "variables",
        json.parse(c.get("variables")),
        "secret_names",
        c.get("secret_names"));
  }

  @Transactional
  public Object configure(AuthService.Actor a, String project, Config d) {
    var p = r.project(project, a.id());
    r.allocation(text(p, "allocation_id"), a.id());
    r.lockProject(project, a.id());
    require(
        d.variables().keySet().stream().allMatch(k -> Set.of("APP_MESSAGE").contains(k)),
        400,
        "CONFIG_NOT_ALLOWED",
        "固定模板只支持APP_MESSAGE普通配置");
    require(
        d.secrets().keySet().stream().allMatch(k -> Set.of("API_TOKEN").contains(k)),
        400,
        "CONFIG_NOT_ALLOWED",
        "固定模板只支持API_TOKEN密钥");
    for (String value : d.variables().values())
      require(
          value != null && value.length() <= 500 && !value.contains("\n") && !value.contains("\r"),
          400,
          "INVALID_CONFIG",
          "配置需要单行且长度不超过500");
    for (String value : d.secrets().values())
      require(
          value != null && value.length() <= 2000 && !value.contains("\n") && !value.contains("\r"),
          400,
          "INVALID_CONFIG",
          "密钥需要单行且长度不超过2000");
    var rows = r.config(project);
    long version = rows.isEmpty() ? 1 : num(rows.get(0), "version") + 1;
    r.config(
        id(),
        project,
        version,
        json.json(new TreeMap<>(d.variables())),
        vault.encrypt(d.secrets()),
        json.json(d.secrets().keySet()));
    events.audit(a.id(), "APP_CONFIG_VERSION", project, "配置版本：" + version + "；不记录内容");
    return config(a, project);
  }

  /** 新手只编辑首页欢迎语时，保留已有访问密钥，避免空表单误删。 */
  @Transactional
  public Object welcome(AuthService.Actor a, String project, WelcomeMessage d) {
    var p = r.project(project, a.id());
    r.allocation(text(p, "allocation_id"), a.id());
    r.lockProject(project, a.id());
    var rows = r.config(project);
    Map<String, String> secrets = new TreeMap<>();
    if (!rows.isEmpty()) vault.decrypt(text(rows.get(0), "secrets_cipher")).forEach((key,value) -> secrets.put(key,value.toString()));
    return configure(a, project, new Config(Map.of("APP_MESSAGE", d.message()), secrets));
  }

  @Transactional
  public Object evidence(AuthService.Actor a, String project) {
    var p = r.project(project, a.id());
    var allocation = r.allocationView(text(p, "allocation_id"), a.id());
    return Map.of(
        "project",
        p,
        "allocation_id",
        allocation.get("id"),
        "observed_at",
        Instant.now().toString(),
        "deployments",
        r.recent(project),
        "notice",
        "未采集的日志或指标不能推断；应用回滚不回滚数据库");
  }

  @Transactional
  public Object diagnose(AuthService.Actor a, String project) {
    var evidence = evidence(a, project);
    var recent = r.recent(project);
    var conclusion = new TreeMap<String, Object>();
    conclusion.put("engine", "EVIDENCE_RULES");
    conclusion.put("status", "INSUFFICIENT_EVIDENCE");
    conclusion.put("message", "需要采集当前运行证据，不能仅凭描述推测故障原因");
    String deployment = null;
    if (!recent.isEmpty()) {
      var t = recent.get(0);
      deployment = text(t, "id");
      conclusion.put("evidence_deployment_id", deployment);
      if (text(t, "state").equals("FAILED")) {
        conclusion.put("status", "DEPLOYMENT_FAILED");
        conclusion.put("message", "最近操作失败，检查该任务事件；尚不能确定根因");
      }
      if (t.get("result") != null) {
        var result = json.parse(t.get("result"));
        Object raw = result.get("evidence");
        if (raw instanceof Map<?, ?> sample) {
          if (Boolean.TRUE.equals(sample.get("oom_killed"))) {
            conclusion.put("status", "OOM_OBSERVED");
            conclusion.put("message", "容器运行状态记录了OOM终止，需核对内存限额及应用用量");
          } else if (Boolean.FALSE.equals(sample.get("running"))) {
            conclusion.put("status", "PROCESS_STOPPED");
            conclusion.put("message", "采样时容器未运行，检查退出状态和最近操作");
          } else if (Boolean.TRUE.equals(sample.get("running"))) {
            conclusion.put("status", "PROCESS_RUNNING");
            conclusion.put("message", "采样时进程运行；不能据此保证所有业务功能正常");
          }
        }
      }
    }
    String id = id();
    r.diagnose(id, project, a.id(), deployment, json.json(evidence), json.json(conclusion));
    return Map.of("id", id, "conclusion", conclusion, "evidence", evidence);
  }

  @Transactional
  public Object create(AuthService.Actor a, Project d) {
    r.allocation(d.allocationId(), a.id());
    require(r.projectCount(d.allocationId()) == 0, 409, "PROJECT_LIMIT", "首版每个租约仅支持一个应用项目");
    String id = id();
    r.createProject(id, a.id(), d.name(), d.allocationId());
    return r.project(id, a.id());
  }

  @Transactional
  public Object detail(AuthService.Actor a, String id) {
    var p = r.project(id, a.id());
    var allocation = r.allocationView(text(p, "allocation_id"), a.id());
    var result = new LinkedHashMap<String, Object>(p);
    result.put("instance_id", allocation.get("instance_id"));
    result.put("allocation_state", allocation.get("state"));
    result.put("instance_state", allocation.get("instance_state"));
    result.put("host_mode", allocation.get("host_mode"));
    result.put("expires_at", allocation.get("expires_at"));
    result.put("recent_deployments", r.recent(id));
    result.put("runner_controls_supported", supportsControls(pool.capabilitiesView(text(allocation, "host_id"))));
    if (p.get("current_deployment_id") != null) {
      var release = r.deployment(text(p, "current_deployment_id"), a.id());
      var output = release.get("result") == null ? Map.<String,Object>of() : json.parse(release.get("result"));
      result.put("current_release", Map.of("id", release.get("id"), "published_at", release.get("updated_at"), "url", Objects.toString(output.get("url"), "")));
    }
    return result;
  }

  private boolean supportsControls(Map<String,Object> host) {
    return host != null && host.get("capabilities") != null && "2".equals(Objects.toString(json.parse(host.get("capabilities")).get("execution_protocol"), ""));
  }

  @Transactional
  public Object prepare(AuthService.Actor a, String project, Plan d) {
    var p = r.project(project, a.id());
    var allocation = r.allocation(text(p, "allocation_id"), a.id());
    if (Set.of("START", "STOP").contains(d.kind())) {
      require(p.get("current_deployment_id") != null, 409, "APP_NOT_DEPLOYED", "应用尚未部署");
      require(supportsControls(pool.host(text(allocation, "host_id"))), 409, "RUNNER_UPDATE_REQUIRED", "执行器需要更新后才能启动或停止应用");
    }
    Map<String, Object> payload = new TreeMap<>();
    if (d.kind().equals("ROLLBACK")) {
      var target = r.deployment(d.targetDeploymentId(), a.id());
      require(
          project.equals(target.get("project_id"))
              && text(target, "state").equals("SUCCEEDED")
              && Set.of("DEPLOY", "ROLLBACK").contains(text(target, "kind")),
          409,
          "ROLLBACK_TARGET",
          "只能回滚本项目成功的发布");
      payload.putAll(json.parse(target.get("payload")));
      payload.put("target_deployment_id", target.get("id"));
    } else {
      var capabilities = json.parse(pool.host(text(allocation, "host_id")).get("capabilities"));
      payload.put("template", "spring-react-single-host@1");
      payload.put("backend_image", capabilities.get("backend_digest"));
      payload.put("frontend_image", capabilities.get("frontend_digest"));
    }
    payload.put("project_id", project);
    payload.put("allocation_id", allocation.get("id"));
    payload.put("generation", allocation.get("generation"));
    payload.put("action", d.kind());
    payload.put("base_version", p.get("version"));
    if (d.kind().equals("DEPLOY")) {
      var configs = r.config(project);
      if (!configs.isEmpty()) {
        payload.put("config_id", configs.get(0).get("id"));
        payload.put("variables", json.parse(configs.get(0).get("variables")));
        payload.put("secret_names", configs.get(0).get("secret_names"));
      }
    }
    payload.put("impact", switch (d.kind()) {
      case "LOGS" -> "只读取本项目健康状态与有限脱敏日志";
      case "STOP" -> "停止后应用无法访问；保留版本和配置，租约与计费继续，可再次启动";
      case "START" -> "启动当前已部署版本，不应用尚未部署的新配置";
      default -> "可能短暂中断应用；不会修改数据库结构或删除云实例";
    });
    String id = id(), encoded = json.json(payload), hash = AuthService.hash(encoded);
    r.plan(
        id,
        a.id(),
        project,
        d.kind(),
        encoded,
        hash,
        p.get("version"),
        time(Instant.now().plusSeconds(600)));
    return Map.of(
        "id",
        id,
        "plan_hash",
        hash,
        "snapshot",
        payload,
        "expires_in",
        600,
        "requires_confirmation",
        true);
  }

  @Transactional
  public Object confirm(AuthService.Actor a, String id, Confirm d, String key) {
    require(d.confirmed(), 400, "CONFIRM_REQUIRED", "需要用户明确确认");
    require(
        key != null && key.matches("[A-Za-z0-9_-]{8,100}"), 400, "IDEMPOTENCY_REQUIRED", "需要幂等键");
    var plan = r.plan(id, a.id());
    var p = r.project(text(plan, "project_id"), a.id());
    var allocation = r.allocation(text(p, "allocation_id"), a.id());
    p = r.lockProject(text(p, "id"), a.id());
    require(text(plan, "plan_hash").equals(d.planHash()), 409, "PLAN_CHANGED", "计划摘要不匹配");
    var keyed = r.byKey(a.id(), key);
    if (!keyed.isEmpty()) {
      require(id.equals(keyed.get(0).get("plan_id")), 409, "IDEMPOTENCY_CONFLICT", "同键不同计划");
      return safeTask(keyed.get(0));
    }
    var existing = r.byPlan(id);
    if (!existing.isEmpty()) return safeTask(existing.get(0));
    require(
        text(plan, "state").equals("READY")
            && instant(plan.get("expires_at")).isAfter(Instant.now()),
        409,
        "PLAN_EXPIRED",
        "计划已过期");
    require(
        num(plan, "base_version") == num(p, "version")
            && num(json.parse(plan.get("snapshot")), "generation") == num(allocation, "generation"),
        409,
        "STALE_PLAN",
        "部署或租约版本已改变");
    pool.host(text(allocation, "host_id"));
    require(r.busy(text(allocation, "host_id")) == 0, 409, "DEPLOYMENT_BUSY", "该实例存在执行中或待人工处理任务");
    String task = id();
    r.enqueue(task, plan, allocation, key);
    events.audit(a.id(), "APP_PLAN_CONFIRMED", task, "计划：" + id);
    return safeTask(r.task(task));
  }

  @Transactional
  public Object deployment(AuthService.Actor a, String id) {
    var t = r.deployment(id, a.id());
    var p = r.project(text(t, "project_id"), a.id());
    r.allocationView(text(p, "allocation_id"), a.id());
    return safeTask(t);
  }

  @Transactional
  public Object events(AuthService.Actor a, String id, long after) {
    deployment(a, id);
    return r.events(id, Math.max(0, after));
  }

  private Map<String, Object> safeTask(Map<String, Object> t) {
    var copy = new LinkedHashMap<>(t);
    copy.remove("idempotency_key");
    copy.put("cancel_requested", bool(t.get("cancel_requested")));
    copy.put("commit_started", bool(t.get("commit_started")));
    return copy;
  }

  @Transactional
  public Object dismissPlan(AuthService.Actor a, String id) {
    var plan = r.plan(id, a.id());
    require(Set.of("READY", "CANCELLED").contains(text(plan, "state")), 409, "PLAN_ALREADY_SUBMITTED", "计划已提交，请在部署任务中申请取消");
    r.dismissPlan(id);
    return Map.of("cancelled", true);
  }

  /** 与领取/提交均先锁当前主机，再锁任务；取消与发布切换只有一方能先获得许可。 */
  @Transactional
  public Object cancel(AuthService.Actor a, String id, Cancel d) {
    require(d.confirmed(), 400, "CONFIRM_REQUIRED", "请确认取消本次部署");
    var owned = r.deployment(id, a.id());
    var p = r.project(text(owned, "project_id"), a.id());
    var allocation = r.allocation(text(p, "allocation_id"), a.id());
    var host = pool.host(text(allocation, "host_id"));
    r.lockProject(text(p, "id"), a.id());
    var task = r.task(id);
    require(num(task, "generation") == num(allocation, "generation"), 409, "STALE_ALLOCATION", "租约代次已改变");
    require(Set.of("DEPLOY", "ROLLBACK").contains(text(task, "kind")), 409, "CANCEL_NOT_SUPPORTED", "只允许取消部署或回滚；停止应用请使用停止操作");
    if (text(task, "state").equals("CANCELLED")) return safeTask(task);
    require(Set.of("QUEUED", "RUNNING").contains(text(task, "state")), 409, "TASK_FINISHED", "任务已结束或需要管理员确认，请刷新状态");
    require(!bool(task.get("commit_started")), 409, "PUBLISH_IN_PROGRESS", "已进入发布切换阶段，请等待完成后停止应用或回滚");
    if (text(task, "state").equals("RUNNING"))
      require(supportsControls(host), 409, "RUNNER_UPDATE_REQUIRED", "执行器版本不支持在线取消，请联系管理员更新");
    r.requestCancel(id);
    if (text(task, "state").equals("QUEUED"))
      r.cancelled(id, json.json(Map.of("message", "已取消，尚未开始部署", "cancelled", true)));
    events.audit(a.id(), "APP_CANCEL_REQUESTED", id, "用户确认取消本次部署；不停止已有应用、不删除租约");
    return safeTask(r.task(id));
  }

  @Transactional
  public Object reconcile(AuthService.Actor a, String id, String reason) {
    a.adminOnly();
    require(
        reason != null && reason.length() >= 8 && reason.length() <= 500,
        400,
        "REASON_REQUIRED",
        "请填写已检查的外部执行结果及处置原因");
    var t = r.task(id);
    require(text(t, "state").equals("MANUAL_REQUIRED"), 409, "TASK_STATE", "只允许处理超时待人工确认任务");
    // 不自动重放未知副作用；管理员检查实际容器后，仅解除阻塞，再创建新的确认计划。
    r.reconcileFailed(id, json.json(Map.of("message", "管理员对账结束；需要重新准备操作计划")));
    events.audit(a.id(), "APP_EXECUTION_RECONCILED", id, reason);
    return safeTask(r.task(id));
  }

  /** Runner凭据只绑定平台主机；承租人来自当前allocation。超时不自动重跑未知副作用。 */
  @Transactional
  public Object claim(String credential) {
    var h = pool.runner(AuthService.hash(credential));
    pool.heartbeat(text(h, "id"));
    var running = r.running(text(h, "id"));
    if (!running.isEmpty()) {
      var task = running.get(0);
      if (instant(task.get("lease_until")).isBefore(Instant.now())) {
        r.timeout(text(h, "id"));
        return Map.of("blocked", true);
      }
      r.lease(text(h, "id"));
      return Map.of("task", executionTask(task)); // Runner本地执行日志判定恢复或返回已有结果。
    }
    if (r.busy(text(h, "id")) > 0 && r.queued(text(h, "id")).isEmpty())
      return Map.of("blocked", true);
    var rows = r.queued(text(h, "id"));
    if (rows.isEmpty()) return Map.of();
    var t = rows.get(0);
    require(num(t, "generation") == num(h, "generation"), 409, "STALE_ALLOCATION", "执行代次已变化");
    if (!text(t, "kind").equals("CLEANUP"))
      require(
          Set.of("ALLOCATED", "FROZEN").contains(text(h, "pool_state")),
          409,
          "HOST_STATE",
          "主机不可执行");
    r.claim(text(t, "id"));
    return Map.of("task", executionTask(r.task(text(t, "id"))));
  }

  private Map<String, Object> executionTask(Map<String, Object> task) {
    var copy = new LinkedHashMap<>(task);
    var payload = json.parse(task.get("payload"));
    if (payload.get("config_id") != null
        && Set.of("DEPLOY", "ROLLBACK").contains(text(task, "kind"))) {
      var cfg = r.configById(payload.get("config_id").toString());
      require(task.get("project_id").equals(cfg.get("project_id")), 409, "CONFIG_SCOPE", "配置不属于项目");
      copy.put("execution_secrets", vault.decrypt(text(cfg, "secrets_cipher")));
    }
    return copy;
  }

  private Map<String, Object> runnerTask(String credential, String id, long attempt) {
    var h = pool.runner(AuthService.hash(credential));
    var t = r.task(id);
    require(
        text(t, "host_id").equals(h.get("id"))
            && num(t, "generation") == num(h, "generation")
            && num(t, "attempt") == attempt
            && text(t, "state").equals("RUNNING"),
        409,
        "STALE_EXECUTION",
        "任务不属于主机或执行已失效");
    return t;
  }

  @Transactional
  public Object control(String credential, String id, Checkpoint d) {
    var task = runnerTask(credential, id, d.attempt());
    return Map.of("cancel_requested", bool(task.get("cancel_requested")), "commit_started", bool(task.get("commit_started")));
  }

  @Transactional
  public Object beginCommit(String credential, String id, Checkpoint d) {
    var task = runnerTask(credential, id, d.attempt());
    require(Set.of("DEPLOY", "ROLLBACK").contains(text(task, "kind")), 409, "TASK_KIND", "只有发布需要切换许可");
    require(!bool(task.get("cancel_requested")), 409, "CANCEL_REQUESTED", "用户已申请取消，必须清理候选资源后确认");
    r.beginCommit(id);
    return Map.of("accepted", true);
  }

  @Transactional
  public Object heartbeat(String credential) {
    var h = pool.runner(AuthService.hash(credential));
    pool.heartbeat(text(h, "id"));
    r.timeout(text(h, "id"));
    r.lease(text(h, "id"));
    return Map.of("generation", h.get("generation"));
  }

  @Transactional
  public Object event(String credential, String id, Event d) {
    runnerTask(credential, id, d.attempt());
    // Runner只提交固定阶段与脱敏摘要；此处进一步拒绝明显敏感字段。
    require(
        !d.message().matches("(?is).*(password|secret|token|authorization|private.key).*"),
        400,
        "SENSITIVE_EVENT",
        "事件含敏感标识");
    r.event(id, d.attempt(), d.sequence(), d.message());
    return Map.of("accepted", true);
  }

  @Transactional
  public Object complete(String credential, String id, Completion d) {
    var h = pool.runner(AuthService.hash(credential));
    var current = r.task(id);
    if (text(current, "host_id").equals(h.get("id"))
        && num(current, "attempt") == d.attempt()
        && num(current, "generation") == num(h, "generation")
        && Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(text(current, "state"))) return safeTask(current);
    var t = runnerTask(credential, id, d.attempt());
    // 仅接受固定结果字段；不保存任意Runner报文或潜在秘密。
    Map<String, Object> result = new TreeMap<>();
    for (String k :
        List.of("message", "url", "backend_digest", "frontend_digest", "health", "evidence", "cancelled", "candidate_cleanup_confirmed", "manual_required"))
      if (d.result().containsKey(k)) result.put(k, d.result().get(k));
    require(json.json(result).length() <= 16000, 400, "RESULT_TOO_LARGE", "结果超限");
    if (Boolean.TRUE.equals(result.get("cancelled"))) {
      require(!d.success() && bool(t.get("cancel_requested")) && !bool(t.get("commit_started"))
          && Boolean.TRUE.equals(result.get("candidate_cleanup_confirmed")), 409, "CANCEL_NOT_CONFIRMED", "需要确认取消请求和候选资源清理");
      r.cancelled(id, json.json(result));
      events.audit("RUNNER", "APP_TASK_CANCELLED", id, "候选资源已清理，未发布取消的版本");
      return safeTask(r.task(id));
    }
    if (Boolean.TRUE.equals(result.get("manual_required"))) {
      r.manual(id, json.json(result));
      return safeTask(r.task(id));
    }
    if (d.success() && Set.of("DEPLOY", "ROLLBACK", "RESUME", "START", "RESTART").contains(text(t, "kind")))
      require(Boolean.TRUE.equals(result.get("health")), 400, "HEALTH_REQUIRED", "缺少健康验证");
    if (d.success() && Set.of("DEPLOY", "ROLLBACK").contains(text(t, "kind")) && supportsControls(h))
      require(bool(t.get("commit_started")) && !bool(t.get("cancel_requested")), 409, "PUBLISH_NOT_AUTHORIZED", "缺少发布切换许可");
    r.finish(id, d.success(), json.json(result));
    if (text(t, "kind").equals("CLEANUP")) {
      r.cleanup(h, d.success());
      pool.refreshCapacity(text(h, "sku_id"));
    } else if (d.success() && Set.of("DEPLOY", "ROLLBACK").contains(text(t, "kind"))) {
      r.publish(text(t, "project_id"), id);
      r.runtime(text(t, "project_id"), "RUNNING");
    } else if (d.success() && text(t, "kind").equals("STOP")) {
      r.runtime(text(t, "project_id"), "STOPPED");
    } else if (d.success() && Set.of("START", "RESTART").contains(text(t, "kind"))) {
      r.runtime(text(t, "project_id"), "RUNNING");
    } else if (d.success() && text(t, "kind").equals("LOGS")) {
      Object evidence = result.get("evidence");
      String status = Boolean.TRUE.equals(result.get("health")) ? "RUNNING"
          : evidence instanceof Map<?,?> sample && Boolean.FALSE.equals(sample.get("running")) ? "STOPPED" : "UNKNOWN";
      r.runtime(text(t, "project_id"), status);
    } else if (!d.success() && t.get("project_id") != null) {
      r.runtime(text(t, "project_id"), "UNKNOWN");
    }
    events.audit("RUNNER", "APP_TASK_FINISHED", id, d.success() ? "成功" : "失败");
    return safeTask(r.task(id));
  }
}
