package com.cloudagent.business.controller;

import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.dto.UploadData;
import com.cloudagent.business.service.AuthService;
import com.cloudagent.business.service.CatalogService;
import com.cloudagent.business.service.ListQueryService;
import com.cloudagent.business.service.ResourceService;
import com.cloudagent.business.service.SupportService;
import com.cloudagent.business.service.TradeService;
import com.cloudagent.business.web.ApiFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户端主要 REST 接口：商品、报价、订单、付款、实例、售后、工单和通知。 {@code @RestController} 的返回值由 Spring/Jackson 序列化成
 * JSON，不是直接返回网页 HTML。 本类只负责 HTTP 参数交接与响应；业务、权限和数据访问分别交给 Service、Repository。
 */
@RestController
public class PlatformController {
  private final ListQueryService queries;
  private final CatalogService catalog;
  private final TradeService trade;
  private final ResourceService resources;
  private final SupportService support;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public PlatformController(
      ListQueryService queries,
      CatalogService catalog,
      TradeService trade,
      ResourceService resources,
      SupportService support) {
    this.queries = queries;
    this.catalog = catalog;
    this.trade = trade;
    this.resources = resources;
    this.support = support;
  }

  /** 读取ApiFilter写入的可信操作者；它不是从HTTP参数解析的user_id。 */
  static AuthService.Actor actor(HttpServletRequest r) {
    return ApiFilter.actor(r);
  }

  /** 把业务结果包成统一响应，实际JSON序列化由Spring完成。 */
  static Object ok(Object value) {
    return ApiFilter.result(value);
  }

  /** 公开产品列表入口，查询字符串交给CatalogService构造白名单筛选和分页。 */
  @GetMapping("/api/products")
  public Object products(@RequestParam Map<String, String> q) {
    return ok(catalog.products(q));
  }

  /** 公开产品详情入口，只返回已上架SPU。 */
  @GetMapping("/api/products/{id}")
  public Object product(@PathVariable String id) {
    return ok(catalog.product(id));
  }

  /** 查询某个公开产品的可售配置和可售库存。 */
  @GetMapping("/api/products/{id}/skus")
  public Object skus(@PathVariable String id) {
    return ok(catalog.skus(id));
  }

  /** 从启用SKU中列出地域和可用区；返回值由数据库决定。 */
  @GetMapping("/api/regions")
  public Object regions() {
    return ok(catalog.regions());
  }

  /** 返回本地P0支持的镜像枚举；报价时仍会按具体SKU再次校验。 */
  @GetMapping("/api/images")
  public Object images() {
    return ok(catalog.images());
  }

  /** 创建本人报价，参数来自JSON；新购时instance传null。 */
  @PostMapping("/api/quotes")
  public Object quote(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ok(trade.quote(actor(r), d, null));
  }

  /** 根据路径ID读取本人的已保存报价。 */
  @GetMapping("/api/quotes/{id}")
  public Object quote(@PathVariable String id, HttpServletRequest r) {
    return ok(trade.owned("quotes", id, actor(r).id(), false));
  }

  /** 创建新购或续费订单：JSON只引用quote_id，Idempotency-Key来自请求头。 */
  @PostMapping({"/api/orders", "/api/renewal-orders"})
  public Object order(
      @RequestBody Map<String, Object> d,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return ok(trade.createOrder(actor(r), d, key));
  }

  /** 根据路径ID读取本人订单及其附属记录。 */
  @GetMapping("/api/orders/{id}")
  public Object order(@PathVariable String id, HttpServletRequest r) {
    return ok(trade.detail(actor(r), id));
  }

  /** 提交本人未支付订单取消请求，实际库存释放在交易服务事务内完成。 */
  @PostMapping("/api/orders/{id}/cancel")
  public Object cancel(@PathVariable String id, HttpServletRequest r) {
    return ok(trade.cancel(actor(r), id));
  }

  /** 为本人可付款订单创建或复用支付单，尚不代表已支付。 */
  @PostMapping("/api/orders/{id}/payments")
  public Object pay(@PathVariable String id, HttpServletRequest r) {
    return ok(trade.newPayment(actor(r), id));
  }

  /** 查询本人支付单的商户侧处理状态。 */
  @GetMapping("/api/payments/{id}")
  public Object payment(@PathVariable String id, HttpServletRequest r) {
    return ok(trade.owned("payments", id, actor(r).id(), false));
  }

  /** 模拟渠道结果控制入口，需本人归属和confirmed=true；它只更新渠道台账，不直接把订单改成成功。 */
  @PostMapping("/api/payments/{id}/simulate")
  public Object simulate(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ok(trade.simulate(id, d, actor(r)));
  }

  @GetMapping({
    "/api/orders",
    "/api/instances",
    "/api/payments",
    "/api/refunds",
    "/api/tickets",
    "/api/notifications"
  })
  /** 从固定路由映射推导资源类型，再按认证用户查询列表。 */
  public Object list(HttpServletRequest r, @RequestParam Map<String, String> q) {
    String type = r.getRequestURI().substring("/api/".length());
    return ok(queries.userPage(actor(r), type, q));
  }

  /** 读取本人实例与操作/续费历史。 */
  @GetMapping("/api/instances/{id}")
  public Object instance(@PathVariable String id, HttpServletRequest r) {
    return ok(resources.detail(actor(r), id));
  }

  /** 先校验实例归属，再明确返回暂无监控采样；不生成虚构CPU使用率。 */
  @GetMapping("/api/instances/{id}/metrics")
  public Object metrics(@PathVariable String id, HttpServletRequest r) {
    return ok(resources.metrics(id, actor(r)));
  }

  /** 把动作JSON、路径实例ID和请求头幂等键交给资源服务。 */
  @PostMapping("/api/instances/{id}/actions")
  public Object action(
      @PathVariable String id,
      @RequestBody Map<String, Object> d,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      HttpServletRequest r) {
    return ok(resources.action(actor(r), id, d, key));
  }

  /** 读取本人实例原配置，为指定续费周期报价；不是收到请求就延长有效期。 */
  @PostMapping("/api/instances/{id}/renewal-quotes")
  public Object renewal(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ok(trade.quote(actor(r), d, id));
  }

  /** 提交本人退款申请，实际金额由服务端重新计算。 */
  @PostMapping("/api/refunds")
  public Object refund(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ok(resources.requestRefund(actor(r), d));
  }

  /** 在提交前展示服务端计算的预计可退金额。 */
  @GetMapping("/api/orders/{id}/refund-preview")
  public Object refundPreview(
      @PathVariable String id, @RequestParam String item_id, HttpServletRequest r) {
    return ok(resources.previewRefund(actor(r), id, item_id));
  }

  /** 查询本人的退款申请及渠道处理状态。 */
  @GetMapping("/api/refunds/{id}")
  public Object refund(@PathVariable String id, HttpServletRequest r) {
    return ok(trade.owned("refunds", id, actor(r).id(), false));
  }

  /** 新建本人工单并校验关联订单/实例。 */
  @PostMapping("/api/tickets")
  public Object ticket(@RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ok(support.create(actor(r), d));
  }

  /** 读取本人工单、回复和受保护附件元信息。 */
  @GetMapping("/api/tickets/{id}")
  public Object ticket(@PathVariable String id, HttpServletRequest r) {
    return ok(support.detail(actor(r), id, false));
  }

  /** 用户追加工单回复，staff模式固定为false。 */
  @PostMapping("/api/tickets/{id}/replies")
  public Object reply(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ok(support.reply(actor(r), id, d, false));
  }

  /** 用户关闭本人工单；不能借此设置任意后台状态或分派他人。 */
  @PatchMapping("/api/tickets/{id}")
  public Object close(
      @PathVariable String id, @RequestBody Map<String, Object> d, HttpServletRequest r) {
    return ok(support.update(actor(r), id, d, false));
  }

  /** 读取multipart中的file部分，保存到本人工单。 */
  @PostMapping("/api/tickets/{id}/attachments")
  public Object upload(
      @PathVariable String id, @RequestParam MultipartFile file, HttpServletRequest r)
      throws Exception {
    return ok(
        support.upload(
            actor(r), id, new UploadData(file.getOriginalFilename(), file.getBytes()), false));
  }

  /** 读取受限头像上传，复用附件存储和类型/大小验证。 */
  @PostMapping("/api/me/avatar")
  public Object avatar(@RequestParam MultipartFile file, HttpServletRequest r) throws Exception {
    return ok(
        support.upload(
            actor(r), null, new UploadData(file.getOriginalFilename(), file.getBytes()), true));
  }

  /** 归属/运营校验后返回附件字节，并使用attachment响应头提示浏览器下载，而非直接当作HTML执行。 */
  @GetMapping("/api/attachments/{id}")
  public ResponseEntity<byte[]> download(@PathVariable String id, HttpServletRequest r) {
    var f = support.attachment(actor(r), id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(text(f, "content_type")))
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(text(f, "name"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("Cache-Control", "no-store")
        .body((byte[]) f.get("bytes"));
  }

  /** 只按当前用户更新通知已读状态；不是拿到通知ID就能修改任意用户记录。 */
  @PostMapping("/api/notifications/{id}/read")
  public Object read(@PathVariable String id, HttpServletRequest r) {
    support.markNotificationRead(actor(r), id);
    return ok(null);
  }
}
