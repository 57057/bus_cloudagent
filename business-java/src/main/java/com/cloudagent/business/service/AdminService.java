package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.JsonCodec;
import com.cloudagent.business.repository.AdminRepository;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营和管理员接口，统一挂在 /api/admin 下。 普通用户即使手工拼出后台 URL，也必须通过 staffOnly/adminOnly 检查才可访问。
 * 运营可以维护商品和处理售后；用户授权、任务人工重试及部分审计查询要求管理员。 修改操作记录原因、操作者与前后信息；这与前端是否显示管理按钮是两层不同的保护。
 */
@org.springframework.stereotype.Service
public class AdminService {
  private final JsonCodec json;
  private final BusinessEvents events;

  private final AdminRepository repository;
  private final ListQueryService queries;
  private final CatalogService catalog;
  private final ResourceService resources;
  private final SupportService support;
  private final JobService jobs;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public AdminService(
      JsonCodec json,
      BusinessEvents events,
      AdminRepository repository,
      ListQueryService queries,
      CatalogService catalog,
      ResourceService resources,
      SupportService support,
      JobService jobs) {
    this.json = json;
    this.events = events;

    this.repository = repository;
    this.queries = queries;
    this.catalog = catalog;
    this.resources = resources;
    this.support = support;
    this.jobs = jobs;
  }

  /** 运营工作台聚合指标；paid_cents是成功付款累计额，不是扣除退款后的净收入。 */
  public Object summary(AuthService.Actor a) {
    a.staffOnly();
    return Map.of(
        "users",
        repository.countUsers(),
        "orders",
        repository.countOrders(),
        "instances",
        repository.countUnreleasedInstances(),
        "paid_cents",
        repository.sumSuccessfulPayments(),
        "pending_refunds",
        repository.countSubmittedRefunds(),
        "manual_tasks",
        repository.countManualTasks());
  }

  /** 后台受控分页，先验运营角色，再对用户/审计/outbox等类别要求管理员；资源别名映射到固定表白名单。 */
  public Object list(String resource, Map<String, String> q, AuthService.Actor a) {
    a.staffOnly();
    String type =
        switch (resource) {
          case "inventory" -> "skus";
          case "audit-logs" -> "audits";
          case "price-plans" -> "price_versions";
          case "payment-events" -> "payment_events";
          case "legacy-orders" -> "legacy_orders";
          case "notification-templates" -> "notification_templates";
          default -> resource;
        };
    if (Set.of("audits", "outbox", "users").contains(type)) a.adminOnly();
    return queries.page(type, null, q);
  }

  /** 后台保存商品，具体角色、字段及快照规则由CatalogService处理。 */
  public Object product(Map<String, Object> d, AuthService.Actor a) {
    return catalog.saveProduct(a, d);
  }

  /** 在后台角色检查后组合任意订单的逐台子项、支付单、任务和退款记录。 */
  public Object order(String id, AuthService.Actor a) {
    a.staffOnly();
    var order = new LinkedHashMap<>(repository.findOrder(id));
    for (String table : List.of("items", "payments", "tasks", "refunds"))
      order.put(table, repository.listOrderRecords(table, id));
    return order;
  }

  /** 后台维护规格、价格和库存；不能直接改已售实例的历史配置。 */
  public Object sku(Map<String, Object> d, AuthService.Actor a) {
    return catalog.saveSku(a, d);
  }

  /** 修改已存在的通知模板并增加版本；要求保留原始标题/内容占位符，以保留模拟标识和业务信息。 */
  @Transactional
  public Object template(Map<String, Object> d, AuthService.Actor a) {
    a.staffOnly();
    String id = input(d, "id", 40),
        title = input(d, "title_template", 100),
        content = input(d, "content_template", 800),
        reason = input(d, "reason", 500);
    require(
        title.contains("{title}") && content.contains("{content}"),
        400,
        "INVALID_TEMPLATE",
        "标题和正文须保留{title}与{content}占位符，确保模拟标识和业务详情可见");
    var old = repository.lockNotificationTemplate(id);
    repository.updateNotificationTemplate(title, content, id);
    events.audit(
        a.id(),
        "NOTIFICATION_TEMPLATE_UPDATED",
        id,
        "前：" + json.json(old) + "；后：" + title + " / " + content + "；原因：" + reason);
    return repository.findNotificationTemplate(id);
  }

  /** 仅管理员修改账号启用状态和角色，撤销该用户会话并审计；不允许管理员从这里停用或降权自己。 */
  @Transactional
  public Object user(String id, Map<String, Object> d, AuthService.Actor a) {
    a.adminOnly();
    String reason = input(d, "reason", 500);
    var u = repository.lockUser(id);
    String role = Objects.toString(d.get("role"), u.get("role").toString());
    require(Set.of("USER", "OPERATOR", "ADMIN").contains(role), 400, "INVALID_ROLE", "无效角色");
    boolean enabled =
        d.containsKey("enabled") ? Boolean.TRUE.equals(d.get("enabled")) : bool(u.get("enabled"));
    require(
        !a.id().equals(id) || enabled && role.equals("ADMIN"),
        409,
        "SELF_LOCKOUT",
        "不能撤销自己的管理员权限或停用自己");
    repository.updateUserAccess(role, enabled, id);
    repository.revokeUserSessions(id);
    events.audit(
        a.id(),
        "USER_UPDATED",
        id,
        "原因：" + reason + "；前：" + json.json(u) + "；后：" + role + "," + enabled);
    return null;
  }

  /** 运营审核退款，是否真正退款成功由后台任务和渠道结果决定。 */
  public Object review(String id, Map<String, Object> d, AuthService.Actor a) {
    return resources.review(a, id, d);
  }

  /** 后台读取工单，SupportService的staff模式会再校验角色。 */
  public Object ticket(String id, AuthService.Actor a) {
    return support.detail(a, id, true);
  }

  /** 后台人员回复工单并选择允许的流转状态。 */
  public Object reply(String id, Map<String, Object> d, AuthService.Actor a) {
    return support.reply(a, id, d, true);
  }

  /** 把工单分派给有效运营/管理员，不允许把任意普通用户设成处理人。 */
  public Object assign(String id, Map<String, Object> d, AuthService.Actor a) {
    return support.update(a, id, d, true);
  }

  /** 管理员为人工处理任务/outbox事件申请重试，必须提供原因。 */
  public Object retry(String id, Map<String, Object> d, AuthService.Actor a, boolean outbox) {
    return jobs.retry(a, id, input(d, "reason", 500), outbox);
  }

  /** 仅管理员可配置模拟资源故障，方便验证失败和超时恢复；它不是修改真实云供应商状态的接口。 */
  public Object fault(Map<String, Object> d, AuthService.Actor a) {
    a.adminOnly();
    String reason = input(d, "reason", 500),
        key = input(d, "business_key", 150),
        mode = input(d, "mode", 32);
    require(
        Set.of("FAIL", "TIMEOUT_AFTER_COMMIT", "CLEAR").contains(mode),
        400,
        "INVALID_INPUT",
        "无效模拟故障类型");
    if (mode.equals("CLEAR")) repository.deleteSimulationFault(key);
    else repository.saveSimulationFault(key, mode);
    events.audit(a.id(), "SIMULATION_FAULT", key, "模式：" + mode + "；原因：" + reason);
    return null;
  }
}
