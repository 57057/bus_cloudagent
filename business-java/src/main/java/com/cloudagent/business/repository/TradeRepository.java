package com.cloudagent.business.repository;

import com.cloudagent.business.common.ApiError;
import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * TradeService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC 类型；参数数量固定，值不拼接进
 * SQL。
 */
@Repository
public class TradeRepository {
  private final SqlExecutor s;

  public TradeRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 读取单条记录（不存在时抛出404）：findOwned。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findOwned(String table, boolean lock, Object id, Object user) {
    ApiError.require(
        Set.of("orders", "payments", "instances", "quotes", "refunds", "tickets", "chat_sessions")
            .contains(table),
        500,
        "INTERNAL_ERROR",
        "非法数据表");
    return s.one(
        "SELECT * FROM p0_" + table + " WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
        id,
        user);
  }

  /** 执行参数绑定的写入并返回影响行数：insertQuote。锁和条件更新在调用方业务事务中生效。 */
  public int insertQuote(
      Object id,
      Object userId,
      Object skuId,
      Object kind,
      Object instanceId,
      Object config,
      Object snapshot,
      Object unitCents,
      Object totalCents,
      Object quantity,
      Object months,
      Object expiresAt) {
    return s.run(
        "INSERT INTO p0_quotes VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        userId,
        skuId,
        kind,
        instanceId,
        config,
        snapshot,
        unitCents,
        totalCents,
        quantity,
        months,
        expiresAt);
  }

  /** 读取单条记录（不存在时抛出404）：lockUser。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockUser(Object userId) {
    return s.one("SELECT id FROM p0_users WHERE id=? FOR UPDATE", userId);
  }

  /** 读取记录列表：findOrderByIdempotencyKey。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findOrderByIdempotencyKey(Object userId, Object key) {
    return s.list("SELECT * FROM p0_orders WHERE user_id=? AND idempotency_key=?", userId, key);
  }

  /** 读取聚合数值：countUnreleasedInstances。锁和条件更新在调用方业务事务中生效。 */
  public long countUnreleasedInstances(Object userId) {
    return s.count(
        "SELECT COUNT(*) FROM p0_instances WHERE user_id=? AND state<>'RELEASED'", userId);
  }

  /** 读取聚合数值：sumReservedInstances。锁和条件更新在调用方业务事务中生效。 */
  public long sumReservedInstances(Object userId) {
    return s.count(
        "SELECT COALESCE(SUM(quantity),0) FROM p0_orders WHERE user_id=? AND"
            + " kind='NEW_PURCHASE' AND state='PENDING_PAYMENT'",
        userId);
  }

  /** 读取聚合数值：countUnprovisionedItems。锁和条件更新在调用方业务事务中生效。 */
  public long countUnprovisionedItems(Object userId) {
    return s.count(
        "SELECT COUNT(*) FROM p0_items i JOIN p0_orders o ON o.id=i.order_id LEFT JOIN"
            + " p0_instances r ON r.id=i.instance_id WHERE o.user_id=? AND"
            + " o.kind='NEW_PURCHASE' AND o.state IN"
            + " ('PAID','FULFILLING','PARTIALLY_FULFILLED','FULFILLMENT_FAILED') AND"
            + " i.refunded=FALSE AND r.id IS NULL",
        userId);
  }

  /** 执行参数绑定的写入并返回影响行数：reserveStock。锁和条件更新在调用方业务事务中生效。 */
  public int reserveStock(Object quantity, Object skuId, Object quantity3) {
    return s.run(
        "UPDATE p0_skus SET reserved=reserved+? WHERE id=? AND"
            + " capacity-reserved-allocated>=? AND active=TRUE",
        quantity,
        skuId,
        quantity3);
  }

  /** 读取聚合数值：countPendingRenewals。锁和条件更新在调用方业务事务中生效。 */
  public long countPendingRenewals(Object id) {
    return s.count(
        "SELECT COUNT(*) FROM p0_orders WHERE instance_id=? AND kind='RENEWAL' AND state"
            + " IN ('PENDING_PAYMENT','PAID','FULFILLING')",
        id);
  }

  /** 读取聚合数值：countPendingRefunds。锁和条件更新在调用方业务事务中生效。 */
  public long countPendingRefunds(Object id) {
    return s.count(
        "SELECT COUNT(*) FROM p0_refunds WHERE item_id IN (SELECT id FROM p0_items WHERE"
            + " instance_id=?) AND state NOT IN ('REJECTED','SUCCEEDED')",
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：insertOrder。锁和条件更新在调用方业务事务中生效。 */
  public int insertOrder(
      Object id,
      Object userId,
      Object quoteId,
      Object idempotencyKey,
      Object requestHash,
      Object skuId,
      Object kind,
      Object instanceId,
      Object state,
      Object config,
      Object snapshot,
      Object unitCents,
      Object totalCents,
      Object quantity,
      Object months,
      Object paymentDeadline,
      Object createdAt) {
    return s.run(
        "INSERT INTO"
            + " p0_orders(id,user_id,quote_id,idempotency_key,request_hash,sku_id,kind,instance_id,state,config,snapshot,unit_cents,total_cents,quantity,months,payment_deadline,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        userId,
        quoteId,
        idempotencyKey,
        requestHash,
        skuId,
        kind,
        instanceId,
        state,
        config,
        snapshot,
        unitCents,
        totalCents,
        quantity,
        months,
        paymentDeadline,
        createdAt);
  }

  /** 执行参数绑定的写入并返回影响行数：insertItem。锁和条件更新在调用方业务事务中生效。 */
  public int insertItem(
      Object id,
      Object orderId,
      Object userId,
      Object instanceId,
      Object amountCents,
      Object state) {
    return s.run(
        "INSERT INTO p0_items(id,order_id,user_id,instance_id,amount_cents,state)"
            + " VALUES(?,?,?,?,?,?)",
        id,
        orderId,
        userId,
        instanceId,
        amountCents,
        state);
  }

  /** 读取记录列表：listOrderItems。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listOrderItems(Object id) {
    return s.list("SELECT * FROM p0_items WHERE order_id=?", id);
  }

  /** 读取记录列表：listOrderPayments。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listOrderPayments(Object id) {
    return s.list("SELECT * FROM p0_payments WHERE order_id=?", id);
  }

  /** 读取记录列表：listOrderTasks。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listOrderTasks(Object id) {
    return s.list("SELECT * FROM p0_tasks WHERE order_id=?", id);
  }

  /** 读取记录列表：listOrderRefunds。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listOrderRefunds(Object id) {
    return s.list("SELECT * FROM p0_refunds WHERE order_id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：closePendingOrder。锁和条件更新在调用方业务事务中生效。 */
  public int closePendingOrder(Object state, Object id) {
    return s.run("UPDATE p0_orders SET state=? WHERE id=? AND state='PENDING_PAYMENT'", state, id);
  }

  /** 执行参数绑定的写入并返回影响行数：releaseReservedStock。锁和条件更新在调用方业务事务中生效。 */
  public int releaseReservedStock(Object quantity, Object skuId, Object quantity3) {
    return s.run(
        "UPDATE p0_skus SET reserved=reserved-? WHERE id=? AND reserved>=?",
        quantity,
        skuId,
        quantity3);
  }

  /** 执行参数绑定的写入并返回影响行数：cancelItems。锁和条件更新在调用方业务事务中生效。 */
  public int cancelItems(Object id) {
    return s.run("UPDATE p0_items SET state='CANCELLED' WHERE order_id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：closePayments。锁和条件更新在调用方业务事务中生效。 */
  public int closePayments(Object id) {
    return s.run(
        "UPDATE p0_payments SET state='CLOSED' WHERE order_id=? AND state IN"
            + " ('CREATED','PROCESSING')",
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：insertPayment。锁和条件更新在调用方业务事务中生效。 */
  public int insertPayment(
      Object id,
      Object orderId,
      Object userId,
      Object amountCents,
      Object channel,
      Object createdAt) {
    return s.run(
        "INSERT INTO p0_payments(id,order_id,user_id,amount_cents,channel,state,created_at)"
            + " VALUES(?,?,?,?,?,'CREATED',?)",
        id,
        orderId,
        userId,
        amountCents,
        channel,
        createdAt);
  }

  /** 读取单条记录（不存在时抛出404）：findPayment。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findPayment(Object payment) {
    return s.one("SELECT * FROM p0_payments WHERE id=?", payment);
  }

  /** 读取单条记录（不存在时抛出404）：lockOrder。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockOrder(Object orderId) {
    return s.one("SELECT * FROM p0_orders WHERE id=? FOR UPDATE", orderId);
  }

  /** 读取单条记录（不存在时抛出404）：lockPayment。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> lockPayment(Object payment) {
    return s.one("SELECT * FROM p0_payments WHERE id=? FOR UPDATE", payment);
  }

  /** 读取记录列表：findProviderEvents。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findProviderEvents(Object provider) {
    return s.list("SELECT * FROM p0_payment_events WHERE provider_id=?", provider);
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
        "INSERT INTO p0_payment_events VALUES(?,?,?,?,?,?)",
        id,
        paymentId,
        providerId,
        amountCents,
        outcome,
        createdAt);
  }

  /** 执行参数绑定的写入并返回影响行数：markPaymentSucceeded。锁和条件更新在调用方业务事务中生效。 */
  public int markPaymentSucceeded(Object provider, Object payment) {
    return s.run(
        "UPDATE p0_payments SET state='SUCCEEDED',provider_id=? WHERE id=?", provider, payment);
  }

  /** 执行参数绑定的写入并返回影响行数：markOrderFulfilling。锁和条件更新在调用方业务事务中生效。 */
  public int markOrderFulfilling(Object id) {
    return s.run("UPDATE p0_orders SET state='FULFILLING' WHERE id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：allocateReservedStock。锁和条件更新在调用方业务事务中生效。 */
  public int allocateReservedStock(
      Object quantity, Object quantity2, Object skuId, Object quantity4) {
    return s.run(
        "UPDATE p0_skus SET reserved=reserved-?,allocated=allocated+? WHERE id=? AND"
            + " reserved>=?",
        quantity,
        quantity2,
        skuId,
        quantity4);
  }

  /** 执行参数绑定的写入并返回影响行数：insertTask。锁和条件更新在调用方业务事务中生效。 */
  public int insertTask(
      Object id,
      Object businessKey,
      Object type,
      Object orderId,
      Object instanceId,
      Object payload,
      Object nextRun,
      Object createdAt) {
    return s.run(
        "INSERT IGNORE INTO"
            + " p0_tasks(id,business_key,type,order_id,instance_id,payload,state,next_run,created_at)"
            + " VALUES(?,?,?,?,?,?,'PENDING',?,?)",
        id,
        businessKey,
        type,
        orderId,
        instanceId,
        payload,
        nextRun,
        createdAt);
  }
}
