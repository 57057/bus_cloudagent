package com.cloudagent.business.repository;

import com.cloudagent.business.common.ApiError;
import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * AdminService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC 类型；参数数量固定，值不拼接进
 * SQL。
 */
@Repository
public class AdminRepository {
  private final SqlExecutor s;

  public AdminRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 读取聚合数值：countUsers。锁和条件更新在调用方业务事务中生效。 */
  public long countUsers() {
    return s.count("SELECT COUNT(*) FROM p0_users");
  }

  /** 读取聚合数值：countOrders。锁和条件更新在调用方业务事务中生效。 */
  public long countOrders() {
    return s.count("SELECT COUNT(*) FROM p0_orders");
  }

  /** 读取聚合数值：countUnreleasedInstances。锁和条件更新在调用方业务事务中生效。 */
  public long countUnreleasedInstances() {
    return s.count("SELECT COUNT(*) FROM p0_instances WHERE state<>'RELEASED'");
  }

  /** 读取聚合数值：sumSuccessfulPayments。锁和条件更新在调用方业务事务中生效。 */
  public long sumSuccessfulPayments() {
    return s.count("SELECT COALESCE(SUM(amount_cents),0) FROM p0_payments WHERE state='SUCCEEDED'");
  }

  /** 读取聚合数值：countSubmittedRefunds。锁和条件更新在调用方业务事务中生效。 */
  public long countSubmittedRefunds() {
    return s.count("SELECT COUNT(*) FROM p0_refunds WHERE state='SUBMITTED'");
  }

  /** 读取聚合数值：countManualTasks。锁和条件更新在调用方业务事务中生效。 */
  public long countManualTasks() {
    return s.count("SELECT COUNT(*) FROM p0_tasks WHERE state='MANUAL_REQUIRED'");
  }

  /** 读取单条记录（不存在时抛出404）：findOrder。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findOrder(Object id) {
    return s.one("SELECT * FROM p0_orders WHERE id=?", id);
  }

  /** 读取记录列表：listOrderRecords。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listOrderRecords(String table, Object id) {
    ApiError.require(
        Set.of("items", "payments", "tasks", "refunds").contains(table),
        500,
        "INTERNAL_ERROR",
        "非法数据表");
    return s.list("SELECT * FROM p0_" + table + " WHERE order_id=?", id);
  }

  /** 读取单条记录（不存在时抛出404）：lockNotificationTemplate。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockNotificationTemplate(Object id) {
    return s.one("SELECT * FROM p0_notification_templates WHERE id=? FOR UPDATE", id);
  }

  /** 执行参数绑定的写入并返回影响行数：updateNotificationTemplate。锁和条件更新在调用方业务事务中生效。 */
  public int updateNotificationTemplate(Object title, Object content, Object id) {
    return s.run(
        "UPDATE p0_notification_templates SET title_template=?,content_template=?,version=version+1"
            + " WHERE id=?",
        title,
        content,
        id);
  }

  /** 读取单条记录（不存在时抛出404）：findNotificationTemplate。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findNotificationTemplate(Object id) {
    return s.one("SELECT * FROM p0_notification_templates WHERE id=?", id);
  }

  /** 读取单条记录（不存在时抛出404）：lockUser。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockUser(Object id) {
    return s.one("SELECT id,username,role,enabled FROM p0_users WHERE id=? FOR UPDATE", id);
  }

  /** 执行参数绑定的写入并返回影响行数：updateUserAccess。锁和条件更新在调用方业务事务中生效。 */
  public int updateUserAccess(Object role, Object enabled, Object id) {
    return s.run("UPDATE p0_users SET role=?,enabled=? WHERE id=?", role, enabled, id);
  }

  /** 执行参数绑定的写入并返回影响行数：revokeUserSessions。锁和条件更新在调用方业务事务中生效。 */
  public int revokeUserSessions(Object id) {
    return s.run("UPDATE p0_sessions SET revoked=TRUE WHERE user_id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：deleteSimulationFault。锁和条件更新在调用方业务事务中生效。 */
  public int deleteSimulationFault(Object key) {
    return s.run("DELETE FROM p0_simulation_faults WHERE business_key=?", key);
  }

  /** 执行参数绑定的写入并返回影响行数：saveSimulationFault。锁和条件更新在调用方业务事务中生效。 */
  public int saveSimulationFault(Object businessKey, Object mode) {
    return s.run(
        "INSERT INTO p0_simulation_faults VALUES(?,?) ON DUPLICATE KEY UPDATE mode=VALUES(mode)",
        businessKey,
        mode);
  }
}
