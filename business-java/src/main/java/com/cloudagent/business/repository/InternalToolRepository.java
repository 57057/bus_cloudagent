package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * InternalToolService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC
 * 类型；参数数量固定，值不拼接进 SQL。
 */
@Repository
public class InternalToolRepository {
  private final SqlExecutor s;

  public InternalToolRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 读取记录列表：listUserOrders。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listUserOrders(Object user, Object limit) {
    return s.list(
        "SELECT id AS order_id,kind,total_cents,currency,state,created_at FROM (SELECT"
            + " id,kind,total_cents,'CNY' AS currency,state,created_at,user_id FROM"
            + " p0_orders) o WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
        user,
        limit);
  }

  /** 读取记录列表：listUserInstances。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listUserInstances(Object user, Object limit) {
    return s.list(
        "SELECT id AS instance_id,name,sku_id,state,config,public_ip,simulated,expires_at"
            + " FROM p0_instances WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
        user,
        limit);
  }

  /** 读取记录列表：searchAvailableSkus。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> searchAvailableSkus(Object name, Object category) {
    return s.list(
        "SELECT s.*,s.capacity-s.reserved-s.allocated AS available FROM p0_skus s JOIN"
            + " p0_products p ON p.id=s.product_id WHERE p.active=TRUE AND"
            + " s.active=TRUE AND (p.name LIKE ? OR p.category LIKE ?) LIMIT 50",
        name,
        category);
  }

  /** 读取记录列表：findSuccessfulProviderPayment。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findSuccessfulProviderPayment(Object payment, Object provider) {
    return s.list(
        "SELECT * FROM p0_provider_payments WHERE payment_id=? AND transaction_id=? AND"
            + " state='SUCCEEDED'",
        payment,
        provider);
  }
}
