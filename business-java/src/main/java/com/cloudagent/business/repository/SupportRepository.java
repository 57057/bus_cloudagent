package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * SupportService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC
 * 类型；参数数量固定，值不拼接进 SQL。
 */
@Repository
public class SupportRepository {
  private final SqlExecutor s;

  public SupportRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 执行参数绑定的写入并返回影响行数：insertTicket。锁和条件更新在调用方业务事务中生效。 */
  public int insertTicket(
      Object id,
      Object userId,
      Object type,
      Object title,
      Object orderId,
      Object instanceId,
      Object createdAt) {
    return s.run(
        "INSERT INTO p0_tickets(id,user_id,type,title,order_id,instance_id,state,created_at)"
            + " VALUES(?,?,?,?,?,?,'OPEN',?)",
        id,
        userId,
        type,
        title,
        orderId,
        instanceId,
        createdAt);
  }

  /** 执行参数绑定的写入并返回影响行数：insertReply。锁和条件更新在调用方业务事务中生效。 */
  public int insertReply(
      Object id, Object ticketId, Object userId, Object staff, Object content, Object createdAt) {
    return s.run(
        "INSERT INTO p0_replies VALUES(?,?,?,?,?,?)",
        id,
        ticketId,
        userId,
        staff,
        content,
        createdAt);
  }

  /** 读取单条记录（不存在时抛出404）：findTicket。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findTicket(Object id) {
    return s.one("SELECT * FROM p0_tickets WHERE id=?", id);
  }

  /** 读取记录列表：listReplies。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listReplies(Object id) {
    return s.list("SELECT * FROM p0_replies WHERE ticket_id=? ORDER BY created_at,id", id);
  }

  /** 读取记录列表：listAttachments。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listAttachments(Object id) {
    return s.list(
        "SELECT id,name,content_type,created_at FROM p0_attachments WHERE ticket_id=?", id);
  }

  /** 读取单条记录（不存在时抛出404）：lockTicket。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockTicket(Object id) {
    return s.one("SELECT * FROM p0_tickets WHERE id=? FOR UPDATE", id);
  }

  /** 执行参数绑定的写入并返回影响行数：updateTicketState。锁和条件更新在调用方业务事务中生效。 */
  public int updateTicketState(Object state, Object id) {
    return s.run("UPDATE p0_tickets SET state=? WHERE id=?", state, id);
  }

  /** 读取聚合数值：countActiveStaff。锁和条件更新在调用方业务事务中生效。 */
  public long countActiveStaff(Object assignee) {
    return s.count(
        "SELECT COUNT(*) FROM p0_users WHERE id=? AND role IN ('ADMIN','OPERATOR') AND"
            + " enabled=TRUE",
        assignee);
  }

  /** 执行参数绑定的写入并返回影响行数：assignTicket。锁和条件更新在调用方业务事务中生效。 */
  public int assignTicket(Object assignee, Object id) {
    return s.run(
        "UPDATE p0_tickets SET assignee=?,state=IF(state='OPEN','IN_PROGRESS',state) WHERE id=?",
        assignee,
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：closeTicket。锁和条件更新在调用方业务事务中生效。 */
  public int closeTicket(Object id) {
    return s.run("UPDATE p0_tickets SET state='CLOSED' WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：insertAttachment。锁和条件更新在调用方业务事务中生效。 */
  public int insertAttachment(
      Object id,
      Object userId,
      Object ticketId,
      Object name,
      Object contentType,
      Object bytes,
      Object createdAt) {
    return s.run(
        "INSERT INTO p0_attachments VALUES(?,?,?,?,?,?,?)",
        id,
        userId,
        ticketId,
        name,
        contentType,
        bytes,
        createdAt);
  }

  /** 执行参数绑定的写入并返回影响行数：updateAvatar。锁和条件更新在调用方业务事务中生效。 */
  public int updateAvatar(Object id, Object userId) {
    return s.run("UPDATE p0_users SET avatar_id=? WHERE id=?", id, userId);
  }

  /** 读取单条记录（不存在时抛出404）：findAttachment。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findAttachment(Object id) {
    return s.one("SELECT * FROM p0_attachments WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：markNotificationRead。锁和条件更新在调用方业务事务中生效。 */
  public int markNotificationRead(Object id, Object userId) {
    return s.run("UPDATE p0_notifications SET is_read=TRUE WHERE id=? AND user_id=?", id, userId);
  }
}
