package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * InternalIdentity的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC
 * 类型；参数数量固定，值不拼接进 SQL。
 */
@Repository
public class IdentityRepository {
  private final SqlExecutor s;

  public IdentityRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 读取单条记录（不存在时抛出404）：findPreferences。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findPreferences(Object userId) {
    return s.one("SELECT preferences_enabled FROM p0_users WHERE id=?", userId);
  }

  /** 读取聚合数值：countActiveDelegationSessions。锁和条件更新在调用方业务事务中生效。 */
  public long countActiveDelegationSessions(Object authSession, Object userId, Object expiresAt) {
    return s.count(
        "SELECT COUNT(*) FROM p0_sessions ss JOIN p0_users u ON u.id=ss.user_id WHERE"
            + " ss.id=? AND ss.user_id=? AND ss.revoked=FALSE AND ss.expires_at>? AND"
            + " u.enabled=TRUE",
        authSession,
        userId,
        expiresAt);
  }
}
