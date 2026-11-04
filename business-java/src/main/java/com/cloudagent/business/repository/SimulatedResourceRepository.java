package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * SimulatedResourceAdapter的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC
 * 类型；参数数量固定，值不拼接进 SQL。
 */
@Repository
public class SimulatedResourceRepository {
  private final SqlExecutor s;

  public SimulatedResourceRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 执行参数绑定的写入并返回影响行数：insertProviderResource。锁和条件更新在调用方业务事务中生效。 */
  public int insertProviderResource(
      Object businessKey, Object providerId, Object expiresAt, Object updatedAt) {
    return s.run(
        "INSERT IGNORE INTO p0_provider_resources VALUES(?,?,'RUNNING',?,?)",
        businessKey,
        providerId,
        expiresAt,
        updatedAt);
  }

  /** 读取单条记录（不存在时抛出404）：findProviderResource。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findProviderResource(Object key) {
    return s.one("SELECT * FROM p0_provider_resources WHERE business_key=?", key);
  }

  /** 执行参数绑定的写入并返回影响行数：updateProviderState。锁和条件更新在调用方业务事务中生效。 */
  public int updateProviderState(Object state, Object updatedAt, Object key) {
    return s.run(
        "UPDATE p0_provider_resources SET state=?,updated_at=? WHERE business_key=?",
        state,
        updatedAt,
        key);
  }

  /** 执行参数绑定的写入并返回影响行数：renewProviderResource。锁和条件更新在调用方业务事务中生效。 */
  public int renewProviderResource(Object expiresAt, Object updatedAt, Object key) {
    return s.run(
        "UPDATE p0_provider_resources SET"
            + " expires_at=?,state=IF(state='SUSPENDED','RUNNING',state),updated_at=? WHERE"
            + " business_key=?",
        expiresAt,
        updatedAt,
        key);
  }
}
