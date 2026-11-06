package com.cloudagent.business.repository;

import com.cloudagent.business.common.ApiError;
import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * JobRunner的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC 类型；参数数量固定，值不拼接进
 * SQL。
 */
@Repository
public class JobRepository {
  public void poolMetadata(String instance, String endpoint) {
    s.run("UPDATE p0_instances SET public_ip=?,simulated=FALSE WHERE id=?", endpoint, instance);
  }

  private final SqlExecutor s;

  public JobRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 读取记录列表：lockDueWork。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> lockDueWork(String table, Object nextRun) {
    ApiError.require(Set.of("tasks", "outbox").contains(table), 500, "INTERNAL_ERROR", "非法数据表");
    return s.list(
        "SELECT id FROM p0_"
            + table
            + " WHERE state IN ('PENDING','RUNNING') AND next_run<=? ORDER BY next_run"
            + " LIMIT 1 FOR UPDATE",
        nextRun);
  }

  /** 执行参数绑定的写入并返回影响行数：markWorkRunning。锁和条件更新在调用方业务事务中生效。 */
  public int markWorkRunning(String table, Object nextRun, Object id) {
    ApiError.require(Set.of("tasks", "outbox").contains(table), 500, "INTERNAL_ERROR", "非法数据表");
    return s.run("UPDATE p0_" + table + " SET state='RUNNING',next_run=? WHERE id=?", nextRun, id);
  }

  /** 读取单条记录（不存在时抛出404）：lockWork。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockWork(String table, Object id) {
    ApiError.require(Set.of("tasks", "outbox").contains(table), 500, "INTERNAL_ERROR", "非法数据表");
    return s.one("SELECT * FROM p0_" + table + " WHERE id=? FOR UPDATE", id);
  }

  /** 执行参数绑定的写入并返回影响行数：markWorkSucceeded。锁和条件更新在调用方业务事务中生效。 */
  public int markWorkSucceeded(String table, Object id) {
    ApiError.require(Set.of("tasks", "outbox").contains(table), 500, "INTERNAL_ERROR", "非法数据表");
    return s.run("UPDATE p0_" + table + " SET state='SUCCEEDED' WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：recordWorkFailure。锁和条件更新在调用方业务事务中生效。 */
  public int recordWorkFailure(
      String table, Object state, Object attempts, Object nextRun, Object id) {
    ApiError.require(Set.of("tasks", "outbox").contains(table), 500, "INTERNAL_ERROR", "非法数据表");
    return s.run(
        "UPDATE p0_" + table + " SET state=?,attempts=?,next_run=? WHERE id=?",
        state,
        attempts,
        nextRun,
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：updateTaskError。锁和条件更新在调用方业务事务中生效。 */
  public int updateTaskError(Object error, Object id) {
    return s.run("UPDATE p0_tasks SET error=? WHERE id=?", error, id);
  }

  /** 执行参数绑定的写入并返回影响行数：insertNotification。锁和条件更新在调用方业务事务中生效。 */
  public int insertNotification(
      Object id, Object userId, Object eventKey, Object title, Object content, Object createdAt) {
    return s.run(
        "INSERT IGNORE INTO p0_notifications(id,user_id,event_key,title,content,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        userId,
        eventKey,
        title,
        content,
        createdAt);
  }

  /** 读取单条记录（不存在时抛出404）：findPayment。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findPayment(Object paymentId) {
    return s.one("SELECT * FROM p0_payments WHERE id=?", paymentId);
  }

  /** 读取单条记录（不存在时抛出404）：lockOrder。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockOrder(Object orderId) {
    return s.one("SELECT * FROM p0_orders WHERE id=? FOR UPDATE", orderId);
  }

  /** 读取单条记录（不存在时抛出404）：lockItem。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockItem(Object itemId) {
    return s.one("SELECT * FROM p0_items WHERE id=? FOR UPDATE", itemId);
  }

  /** 读取记录列表：findSimulationFault。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findSimulationFault(Object instance) {
    return s.list("SELECT mode FROM p0_simulation_faults WHERE business_key=?", instance);
  }

  /** 读取聚合数值：countProviderResources。锁和条件更新在调用方业务事务中生效。 */
  public long countProviderResources(Object instance) {
    return s.count("SELECT COUNT(*) FROM p0_provider_resources WHERE business_key=?", instance);
  }

  /** 执行参数绑定的写入并返回影响行数：insertInstance。锁和条件更新在调用方业务事务中生效。 */
  public int insertInstance(
      Object id,
      Object userId,
      Object orderId,
      Object skuId,
      Object name,
      Object config,
      Object providerId,
      Object publicIp,
      Object createdAt,
      Object startsAt,
      Object expiresAt,
      Object graceDays) {
    return s.run(
        "INSERT IGNORE INTO"
            + " p0_instances(id,user_id,order_id,sku_id,name,state,config,provider_id,public_ip,simulated,created_at,starts_at,expires_at,grace_days)"
            + " VALUES(?,?,?,?,?,'RUNNING',?,?,?,TRUE,?,?,?,?)",
        id,
        userId,
        orderId,
        skuId,
        name,
        config,
        providerId,
        publicIp,
        createdAt,
        startsAt,
        expiresAt,
        graceDays);
  }

  /** 执行参数绑定的写入并返回影响行数：markItemSucceeded。锁和条件更新在调用方业务事务中生效。 */
  public int markItemSucceeded(Object id) {
    return s.run("UPDATE p0_items SET state='SUCCEEDED' WHERE id=?", id);
  }

  /** 读取单条记录（不存在时抛出404）：lockInstance。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockInstance(Object instanceId) {
    return s.one("SELECT * FROM p0_instances WHERE id=? FOR UPDATE", instanceId);
  }

  /** 执行参数绑定的写入并返回影响行数：insertRenewal。锁和条件更新在调用方业务事务中生效。 */
  public int insertRenewal(
      Object orderId, Object instanceId, Object oldExpires, Object newExpires, Object createdAt) {
    return s.run(
        "INSERT IGNORE INTO p0_renewals VALUES(?,?,?,?,?)",
        orderId,
        instanceId,
        oldExpires,
        newExpires,
        createdAt);
  }

  /** 执行参数绑定的写入并返回影响行数：renewInstance。锁和条件更新在调用方业务事务中生效。 */
  public int renewInstance(Object expiresAt, Object id) {
    return s.run(
        "UPDATE p0_instances SET"
            + " expires_at=?,state=IF(state='SUSPENDED','RUNNING',state),op_version=op_version+1"
            + " WHERE id=?",
        expiresAt,
        id);
  }

  /** 读取聚合数值：countSucceededItems。锁和条件更新在调用方业务事务中生效。 */
  public long countSucceededItems(Object id) {
    return s.count("SELECT COUNT(*) FROM p0_items WHERE order_id=? AND state='SUCCEEDED'", id);
  }

  /** 读取聚合数值：countFailedItems。锁和条件更新在调用方业务事务中生效。 */
  public long countFailedItems(Object id) {
    return s.count(
        "SELECT COUNT(*) FROM p0_items WHERE order_id=? AND state IN"
            + " ('FAILED','MANUAL_REQUIRED')",
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：updateOrderState。锁和条件更新在调用方业务事务中生效。 */
  public int updateOrderState(Object state, Object id) {
    return s.run("UPDATE p0_orders SET state=? WHERE id=?", state, id);
  }

  /** 执行参数绑定的写入并返回影响行数：updateInstanceState。锁和条件更新在调用方业务事务中生效。 */
  public int updateInstanceState(Object target, Object id) {
    return s.run("UPDATE p0_instances SET state=? WHERE id=?", target, id);
  }

  /** 执行参数绑定的写入并返回影响行数：markInstanceReleased。锁和条件更新在调用方业务事务中生效。 */
  public int markInstanceReleased(Object id) {
    return s.run("UPDATE p0_instances SET state='RELEASED',op_version=op_version+1 WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：releaseAllocatedStock。锁和条件更新在调用方业务事务中生效。 */
  public int releaseAllocatedStock(Object skuId) {
    return s.run("UPDATE p0_skus SET allocated=allocated-1 WHERE id=? AND allocated>0", skuId);
  }

  /** 读取聚合数值：countFulfillingRenewals。锁和条件更新在调用方业务事务中生效。 */
  public long countFulfillingRenewals(Object id) {
    return s.count(
        "SELECT COUNT(*) FROM p0_orders WHERE instance_id=? AND kind='RENEWAL' AND"
            + " state='FULFILLING'",
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：suspendInstance。锁和条件更新在调用方业务事务中生效。 */
  public int suspendInstance(Object id) {
    return s.run(
        "UPDATE p0_instances SET state='SUSPENDED',op_version=op_version+1 WHERE id=?", id);
  }

  /** 读取单条记录（不存在时抛出404）：lockRefund。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockRefund(Object id) {
    return s.one("SELECT * FROM p0_refunds WHERE id=? FOR UPDATE", id);
  }

  /** 执行参数绑定的写入并返回影响行数：markRefundProcessing。锁和条件更新在调用方业务事务中生效。 */
  public int markRefundProcessing(Object id) {
    return s.run("UPDATE p0_refunds SET state='RESOURCE_PROCESSING' WHERE id=?", id);
  }

  /** 读取记录列表：lockOptionalInstance。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> lockOptionalInstance(Object instance) {
    return s.list("SELECT * FROM p0_instances WHERE id=? FOR UPDATE", instance);
  }

  /** 执行参数绑定的写入并返回影响行数：markRefundRefunding。锁和条件更新在调用方业务事务中生效。 */
  public int markRefundRefunding(Object id) {
    return s.run("UPDATE p0_refunds SET state='REFUNDING' WHERE id=?", id);
  }

  /** 读取单条记录（不存在时抛出404）：findSuccessfulPayment。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findSuccessfulPayment(Object id) {
    return s.one("SELECT * FROM p0_payments WHERE order_id=? AND state='SUCCEEDED'", id);
  }

  /** 执行参数绑定的写入并返回影响行数：completeRefund。锁和条件更新在调用方业务事务中生效。 */
  public int completeRefund(Object provider, Object id) {
    return s.run("UPDATE p0_refunds SET state='SUCCEEDED',provider_id=? WHERE id=?", provider, id);
  }

  /** 执行参数绑定的写入并返回影响行数：markItemRefunded。锁和条件更新在调用方业务事务中生效。 */
  public int markItemRefunded(Object id) {
    return s.run("UPDATE p0_items SET refunded=TRUE,refund_pending=FALSE WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：applyRefundTotals。锁和条件更新在调用方业务事务中生效。 */
  public int applyRefundTotals(Object amountCents, Object amountCents2, Object id) {
    return s.run(
        "UPDATE p0_orders SET refund_held_cents=refund_held_cents-?,refunded_cents=refunded_cents+?"
            + " WHERE id=?",
        amountCents,
        amountCents2,
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：markItemManual。锁和条件更新在调用方业务事务中生效。 */
  public int markItemManual(Object item) {
    return s.run(
        "UPDATE p0_items SET state='MANUAL_REQUIRED' WHERE id=? AND state<>'SUCCEEDED'", item);
  }

  /** 执行参数绑定的写入并返回影响行数：updateInstanceStateAtVersion。锁和条件更新在调用方业务事务中生效。 */
  public int updateInstanceStateAtVersion(Object oldState, Object instanceId, Object version) {
    return s.run(
        "UPDATE p0_instances SET state=? WHERE id=? AND op_version=?",
        oldState,
        instanceId,
        version);
  }

  /** 执行参数绑定的写入并返回影响行数：markRefundFailed。锁和条件更新在调用方业务事务中生效。 */
  public int markRefundFailed(Object payload) {
    return s.run("UPDATE p0_refunds SET state='FAILED' WHERE id=?", payload);
  }

  /** 读取记录列表：findExpiredOrders。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findExpiredOrders(Object paymentDeadline) {
    return s.list(
        "SELECT id FROM p0_orders WHERE state='PENDING_PAYMENT' AND payment_deadline<=? LIMIT"
            + " 50",
        paymentDeadline);
  }

  /** 读取记录列表：findFailedProviderPayments。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findFailedProviderPayments() {
    return s.list(
        "SELECT p.id,p.user_id,p.amount_cents,r.state AS outcome,r.transaction_id FROM"
            + " p0_payments p JOIN p0_provider_payments r ON r.payment_id=p.id WHERE r.state IN"
            + " ('FAILED','CANCELLED') AND p.state IN ('CREATED','PROCESSING') LIMIT 50");
  }

  /** 执行参数绑定的写入并返回影响行数：closeFailedPayment。锁和条件更新在调用方业务事务中生效。 */
  public int closeFailedPayment(Object state, Object id) {
    return s.run(
        "UPDATE p0_payments SET state=? WHERE id=? AND state IN" + " ('CREATED','PROCESSING')",
        state,
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：insertPaymentEvent。锁和条件更新在调用方业务事务中生效。 */
  public int insertPaymentEvent(
      Object id,
      Object paymentId,
      Object providerId,
      Object amountCents,
      Object outcome,
      Object createdAt) {
    return s.run(
        "INSERT IGNORE INTO p0_payment_events VALUES(?,?,?,?,?,?)",
        id,
        paymentId,
        providerId,
        amountCents,
        outcome,
        createdAt);
  }

  /** 读取记录列表：findUnreconciledSuccesses。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findUnreconciledSuccesses() {
    return s.list(
        "SELECT p.id,r.transaction_id,r.amount_cents FROM p0_payments p JOIN"
            + " p0_provider_payments r ON r.payment_id=p.id WHERE r.state='SUCCEEDED' AND"
            + " p.state<>'SUCCEEDED' LIMIT 50");
  }

  /** 读取记录列表：scanExpiringInstances。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> scanExpiringInstances(Object cursor, Object expiresAt) {
    return s.list(
        "SELECT * FROM p0_instances WHERE id>? AND state NOT IN ('RELEASED','PROVISIONING')"
            + " AND expires_at<=? ORDER BY id LIMIT 1000",
        cursor,
        expiresAt);
  }

  /** 执行参数绑定的写入并返回影响行数：resetItem。锁和条件更新在调用方业务事务中生效。 */
  public int resetItem(Object id) {
    return s.run("UPDATE p0_items SET state='PENDING' WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：markOrderFulfilling。锁和条件更新在调用方业务事务中生效。 */
  public int markOrderFulfilling(Object orderId) {
    return s.run("UPDATE p0_orders SET state='FULFILLING' WHERE id=?", orderId);
  }

  /** 执行参数绑定的写入并返回影响行数：resetWork。锁和条件更新在调用方业务事务中生效。 */
  public int resetWork(String table, Object nextRun, Object id) {
    ApiError.require(Set.of("tasks", "outbox").contains(table), 500, "INTERNAL_ERROR", "非法数据表");
    return s.run(
        "UPDATE p0_" + table + " SET state='PENDING',attempts=0,next_run=? WHERE id=?",
        nextRun,
        id);
  }

  /** 读取单条记录（不存在时抛出404）：findWork。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findWork(String table, Object id) {
    ApiError.require(Set.of("tasks", "outbox").contains(table), 500, "INTERNAL_ERROR", "非法数据表");
    return s.one("SELECT * FROM p0_" + table + " WHERE id=?", id);
  }
}
