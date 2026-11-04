package com.cloudagent.business.controller;

import static com.cloudagent.business.common.Values.*;
import static com.cloudagent.business.web.ApiFilter.*;

import com.cloudagent.business.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * 运营和管理员接口，统一挂在 /api/admin 下。 普通用户即使手工拼出后台 URL，也必须通过 staffOnly/adminOnly 检查才可访问。
 * 运营可以维护商品和处理售后；用户授权、任务人工重试及部分审计查询要求管理员。 修改操作记录原因、操作者与前后信息；这与前端是否显示管理按钮是两层不同的保护。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
  private final AdminService admin;

  public AdminController(AdminService admin) {
    this.admin = admin;
  }

  /** 运营工作台聚合指标；paid_cents是成功付款累计额，不是扣除退款后的净收入。 */
  @GetMapping("/summary")
  public Object summary(HttpServletRequest r) {
    return result(admin.summary(actor(r)));
  }

  /** 后台受控分页，先验运营角色，再对用户/审计/outbox等类别要求管理员；资源别名映射到固定表白名单。 */
  @GetMapping("/{resource}")
  public Object list(
      @PathVariable String resource, @RequestParam Map<String, String> q, HttpServletRequest r) {
    return result(admin.list(resource, q, actor(r)));
  }

  /** 后台保存商品，具体角色、字段及快照规则由CatalogService处理。 */
  @PostMapping("/products")
  public Object product(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.product(d, actor(r)));
  }

  /** 在后台角色检查后组合任意订单的逐台子项、支付单、任务和退款记录。 */
  @GetMapping("/orders/{id}")
  public Object order(@PathVariable String id, HttpServletRequest r) {
    return result(admin.order(id, actor(r)));
  }

  /** 后台维护规格、价格和库存；不能直接改已售实例的历史配置。 */
  @PostMapping("/skus")
  public Object sku(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.sku(d, actor(r)));
  }

  /** 修改已存在的通知模板并增加版本；要求保留原始标题/内容占位符，以保留模拟标识和业务信息。 */
  @PostMapping("/notification-templates")
  public Object template(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.template(d, actor(r)));
  }

  /** 仅管理员修改账号启用状态和角色，撤销该用户会话并审计；不允许管理员从这里停用或降权自己。 */
  @PatchMapping("/users/{id}")
  public Object user(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.user(id, d, actor(r)));
  }

  /** 运营审核退款，是否真正退款成功由后台任务和渠道结果决定。 */
  @PostMapping("/refunds/{id}/review")
  public Object review(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.review(id, d, actor(r)));
  }

  /** 后台读取工单，SupportService的staff模式会再校验角色。 */
  @GetMapping("/tickets/{id}")
  public Object ticket(@PathVariable String id, HttpServletRequest r) {
    return result(admin.ticket(id, actor(r)));
  }

  /** 后台人员回复工单并选择允许的流转状态。 */
  @PostMapping("/tickets/{id}/replies")
  public Object reply(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.reply(id, d, actor(r)));
  }

  /** 把工单分派给有效运营/管理员，不允许把任意普通用户设成处理人。 */
  @PatchMapping("/tickets/{id}")
  public Object assign(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.assign(id, d, actor(r)));
  }

  /** 管理员为人工处理任务/outbox事件申请重试，必须提供原因。 */
  @PostMapping({"/tasks/{id}/retry", "/outbox/{id}/retry"})
  public Object retry(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.retry(id, d, actor(r), r.getRequestURI().contains("/outbox/")));
  }

  /** 仅管理员可配置模拟资源故障，方便验证失败和超时恢复；它不是修改真实云供应商状态的接口。 */
  @PostMapping("/simulation/faults")
  public Object fault(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return result(admin.fault(d, actor(r)));
  }
}
