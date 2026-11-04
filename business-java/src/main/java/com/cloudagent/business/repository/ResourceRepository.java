package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * ResourceService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC
 * 类型；参数数量固定，值不拼接进 SQL。
 */
@Repository
public class ResourceRepository {
  private final SqlExecutor s;

  public ResourceRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 读取记录列表：listInstanceTasks。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listInstanceTasks(Object id) {
    return s.list("SELECT * FROM p0_tasks WHERE instance_id=? ORDER BY created_at DESC", id);
  }

  /** 读取记录列表：listInstanceRenewals。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listInstanceRenewals(Object id) {
    return s.list("SELECT * FROM p0_renewals WHERE instance_id=? ORDER BY created_at DESC", id);
  }

  /** 读取记录列表：findTaskByKey。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findTaskByKey(Object businessKey) {
    return s.list("SELECT * FROM p0_tasks WHERE business_key=?", businessKey);
  }

  /** 读取聚合数值：countPendingRenewals。锁和条件更新在调用方业务事务中生效。 */
  public long countPendingRenewals(Object id) {
    return s.count(
        "SELECT COUNT(*) FROM p0_orders WHERE instance_id=? AND kind='RENEWAL' AND state IN"
            + " ('PENDING_PAYMENT','PAID','FULFILLING')",
        id);
  }

  /** 读取聚合数值：countPendingInstanceRefunds。锁和条件更新在调用方业务事务中生效。 */
  public long countPendingInstanceRefunds(Object id) {
    return s.count(
        "SELECT COUNT(*) FROM p0_refunds f JOIN p0_items i ON i.id=f.item_id WHERE"
            + " i.instance_id=? AND f.state NOT IN ('REJECTED','SUCCEEDED')",
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：updateInstanceOperation。锁和条件更新在调用方业务事务中生效。 */
  public int updateInstanceOperation(Object state, Object version, Object id) {
    return s.run("UPDATE p0_instances SET state=?,op_version=? WHERE id=?", state, version, id);
  }

  /** 读取单条记录（不存在时抛出404）：findTask。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findTask(Object businessKey) {
    return s.one("SELECT * FROM p0_tasks WHERE business_key=?", businessKey);
  }

  /** 执行参数绑定的写入并返回影响行数：insertRefund。锁和条件更新在调用方业务事务中生效。 */
  public int insertRefund(
      Object id,
      Object userId,
      Object orderId,
      Object itemId,
      Object amountCents,
      Object reason,
      Object createdAt) {
    return s.run(
        "INSERT INTO p0_refunds(id,user_id,order_id,item_id,amount_cents,reason,state,created_at)"
            + " VALUES(?,?,?,?,?,?,'SUBMITTED',?)",
        id,
        userId,
        orderId,
        itemId,
        amountCents,
        reason,
        createdAt);
  }

  /** 执行参数绑定的写入并返回影响行数：markItemRefundPending。锁和条件更新在调用方业务事务中生效。 */
  public int markItemRefundPending(Object itemId) {
    return s.run("UPDATE p0_items SET refund_pending=TRUE WHERE id=?", itemId);
  }

  /** 执行参数绑定的写入并返回影响行数：holdRefundAmount。锁和条件更新在调用方业务事务中生效。 */
  public int holdRefundAmount(Object amount, Object order) {
    return s.run(
        "UPDATE p0_orders SET refund_held_cents=refund_held_cents+? WHERE id=?", amount, order);
  }

  /** 读取聚合数值：countSuccessfulPayments。锁和条件更新在调用方业务事务中生效。 */
  public long countSuccessfulPayments(Object order) {
    return s.count(
        "SELECT COUNT(*) FROM p0_payments WHERE order_id=? AND state='SUCCEEDED'", order);
  }

  /** 读取单条记录（不存在时抛出404）：lockOrderItem。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockOrderItem(Object itemId, Object order) {
    return s.one("SELECT * FROM p0_items WHERE id=? AND order_id=? FOR UPDATE", itemId, order);
  }

  /** 读取聚合数值：countInstanceRenewals。锁和条件更新在调用方业务事务中生效。 */
  public long countInstanceRenewals(Object id) {
    return s.count("SELECT COUNT(*) FROM p0_renewals WHERE instance_id=?", id);
  }

  /** 读取聚合数值：countPendingRenewalsForRefund。锁和条件更新在调用方业务事务中生效。 */
  public long countPendingRenewalsForRefund(Object id) {
    return s.count(
        "SELECT COUNT(*) FROM p0_orders WHERE instance_id=? AND kind='RENEWAL' AND state"
            + " IN ('PENDING_PAYMENT','FULFILLING')",
        id);
  }

  /** 读取单条记录（不存在时抛出404）：findRefund。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findRefund(Object id) {
    return s.one("SELECT * FROM p0_refunds WHERE id=?", id);
  }

  /** 读取单条记录（不存在时抛出404）：lockOrder。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockOrder(Object orderId) {
    return s.one("SELECT * FROM p0_orders WHERE id=? FOR UPDATE", orderId);
  }

  /** 读取单条记录（不存在时抛出404）：lockRefund。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockRefund(Object id) {
    return s.one("SELECT * FROM p0_refunds WHERE id=? FOR UPDATE", id);
  }

  /** 执行参数绑定的写入并返回影响行数：reviewRefund。锁和条件更新在调用方业务事务中生效。 */
  public int reviewRefund(Object state, Object reason, Object id) {
    return s.run("UPDATE p0_refunds SET state=?,review_reason=? WHERE id=?", state, reason, id);
  }

  /** 执行参数绑定的写入并返回影响行数：releaseRefundHold。锁和条件更新在调用方业务事务中生效。 */
  public int releaseRefundHold(Object amountCents, Object orderId) {
    return s.run(
        "UPDATE p0_orders SET refund_held_cents=refund_held_cents-? WHERE id=?",
        amountCents,
        orderId);
  }

  /** 执行参数绑定的写入并返回影响行数：clearItemRefundPending。锁和条件更新在调用方业务事务中生效。 */
  public int clearItemRefundPending(Object itemId) {
    return s.run("UPDATE p0_items SET refund_pending=FALSE WHERE id=?", itemId);
  }
}
