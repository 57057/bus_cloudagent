package com.cloudagent.business.service;

import static com.cloudagent.business.common.ApiError.require;
import static com.cloudagent.business.common.Values.*;

import com.cloudagent.business.common.ApiError;
import com.cloudagent.business.dto.UploadData;
import com.cloudagent.business.repository.SupportRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工单、回复、分派、附件与头像的业务服务。 普通用户只能关联和读取本人对象；运营权限由后台入口和 staffOnly 再检查。 附件保存在 MySQL BLOB 中，下载仍需认证；这是本地 P0
 * 方案，大规模文件存储可另行设计。 类型检查结合文件头与图片尺寸，不能只相信客户端声称的 Content-Type 或文件扩展名。
 */
@Service
public class SupportService {
  private final BusinessEvents events;

  private final SupportRepository repository;
  private final TradeService trade;

  /** 构造器：由Spring传入所需依赖，再保存为本对象的字段；Controller/Service通常不需要在业务代码中手动new。 */
  public SupportService(BusinessEvents events, SupportRepository repository, TradeService trade) {
    this.events = events;

    this.repository = repository;
    this.trade = trade;
  }

  /** 检查工单类型与关联对象归属，把工单、首条描述和审计一起写入事务。 */
  @Transactional
  public Object create(AuthService.Actor a, Map<String, Object> d) {
    String type = input(d, "type", 24),
        title = input(d, "title", 100),
        content = input(d, "content", 5000);
    require(
        Set.of("PURCHASE", "PAYMENT", "PROVISION", "RESOURCE", "REFUND").contains(type),
        400,
        "INVALID_INPUT",
        "不支持的工单类型");
    String order = Objects.toString(d.get("order_id"), ""),
        instance = Objects.toString(d.get("instance_id"), "");
    if (!order.isBlank()) trade.owned("orders", order, a.id(), false);
    if (!instance.isBlank()) trade.owned("instances", instance, a.id(), false);
    String id = id();
    repository.insertTicket(
        id,
        a.id(),
        type,
        title,
        order.isBlank() ? null : order,
        instance.isBlank() ? null : instance,
        now());
    repository.insertReply(id(), id, a.id(), false, content, now());
    events.audit(a.id(), "TICKET_CREATED", id, "类型：" + type);
    return detail(a, id, false);
  }

  /** 普通用户查询本人记录，后台staff模式先检查角色；附件列表不返回BLOB正文，内容另走受保护下载接口。 */
  public Object detail(AuthService.Actor a, String id, boolean staff) {
    if (staff) a.staffOnly();
    var ticket =
        new LinkedHashMap<>(
            staff ? repository.findTicket(id) : trade.owned("tickets", id, a.id(), false));
    ticket.put("replies", repository.listReplies(id));
    ticket.put("attachments", repository.listAttachments(id));
    return ticket;
  }

  /** 锁工单并阻止向已关闭工单追问；保存回复、更新流转状态，运营回复通过outbox通知用户。 */
  @Transactional
  public Object reply(AuthService.Actor a, String id, Map<String, Object> d, boolean staff) {
    if (staff) a.staffOnly();
    var t = staff ? repository.lockTicket(id) : trade.owned("tickets", id, a.id(), true);
    require(!text(t, "state").equals("CLOSED"), 409, "TICKET_CLOSED", "工单已关闭");
    String content = input(d, "content", 5000);
    String state = staff ? Objects.toString(d.get("state"), "WAITING_USER") : "IN_PROGRESS";
    require(
        Set.of("IN_PROGRESS", "WAITING_USER", "RESOLVED").contains(state),
        400,
        "INVALID_INPUT",
        "无效工单状态");
    repository.insertReply(id(), id, a.id(), staff, content, now());
    repository.updateTicketState(state, id);
    if (staff)
      events.notice("ticket-reply:" + id(), text(t, "user_id"), "工单收到回复", text(t, "title"));
    events.audit(a.id(), "TICKET_REPLY", id, "状态：" + state);
    return detail(a, id, staff);
  }

  /** 普通用户只能关闭本人工单；后台可以分派给启用的运营/管理员。 */
  @Transactional
  public Object update(AuthService.Actor a, String id, Map<String, Object> d, boolean staff) {
    if (staff) a.staffOnly();
    var t = staff ? repository.lockTicket(id) : trade.owned("tickets", id, a.id(), true);
    if (staff) {
      String assignee = input(d, "assignee", 64);
      require(
          repository.countActiveStaff(assignee) == 1, 400, "INVALID_ASSIGNEE", "分派对象须为有效运营或管理员");
      repository.assignTicket(assignee, id);
    } else {
      require(Boolean.TRUE.equals(d.get("close")), 400, "INVALID_INPUT", "只能关闭本人工单");
      repository.closeTicket(id);
    }
    events.audit(a.id(), staff ? "TICKET_ASSIGN" : "TICKET_CLOSE", id, "前：" + t.get("state"));
    return detail(a, id, staff);
  }

  /** 校验所有者、2MB上限、文件头和图片像素上限，再保存BLOB；头像只允许PNG/JPEG，工单另外允许PDF。 */
  @Transactional
  public Object upload(AuthService.Actor a, String ticket, UploadData file, boolean avatar)
      throws Exception {
    if (!avatar) trade.owned("tickets", ticket, a.id(), false);
    require(
        file.bytes().length > 0 && file.bytes().length <= 2 * 1024 * 1024,
        400,
        "INVALID_ATTACHMENT",
        "文件需小于2MB");
    // 文件头用于识别格式，头像不接受PDF；即使扩展名改成.png，也不能跳过这些服务端检查。
    byte[] bytes = file.bytes();
    String type;
    if (bytes.length >= 8
        && Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      type = "image/png";
    else if (bytes.length >= 3
        && bytes[0] == (byte) 255
        && bytes[1] == (byte) 216
        && bytes[2] == (byte) 255) type = "image/jpeg";
    else if (!avatar
        && bytes.length >= 5
        && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
      type = "application/pdf";
    else throw new ApiError(400, "INVALID_ATTACHMENT", "仅支持PNG/JPEG图片，工单另支持PDF；不支持SVG或HTML");
    // 压缩文件很小也可能声明巨大的图片尺寸，所以另外限制像素数，并在finally释放图片读取器。
    if (type.startsWith("image/")) {
      var reader = javax.imageio.ImageIO.getImageReadersByMIMEType(type);
      require(reader.hasNext(), 400, "INVALID_ATTACHMENT", "图片格式不受支持");
      var r = reader.next();
      try (var input =
          javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
        r.setInput(input);
        require(
            (long) r.getWidth(0) * r.getHeight(0) <= 16000000L,
            400,
            "INVALID_ATTACHMENT",
            "图片尺寸过大");
      } finally {
        r.dispose();
      }
    }
    String id = id(),
        name = Objects.toString(file.originalFilename(), "附件").replaceAll("[\\r\\n\\\\/]", "_");
    if (name.length() > 150) name = name.substring(name.length() - 150);
    repository.insertAttachment(id, a.id(), avatar ? null : ticket, name, type, bytes, now());
    if (avatar) repository.updateAvatar(id, a.id());
    return Map.of("id", id, "name", name);
  }

  /** 返回附件前检查上传者或工单运营权限；拥有运营角色不等于可以下载任何用户的私人头像。 */
  public Map<String, Object> attachment(AuthService.Actor a, String id) {
    var file = repository.findAttachment(id);
    require(
        text(file, "user_id").equals(a.id()) || a.staff() && file.get("ticket_id") != null,
        404,
        "NOT_FOUND",
        "文件不存在或无权访问");
    return file;
  }

  /** 按已认证用户更新通知；不允许跨用户修改。 */
  public void markNotificationRead(AuthService.Actor actor, String id) {
    repository.markNotificationRead(id, actor.id());
  }
}
