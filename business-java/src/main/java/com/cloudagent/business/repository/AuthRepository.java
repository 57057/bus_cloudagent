package com.cloudagent.business.repository;

import com.cloudagent.business.entity.PlatformUser;
import com.cloudagent.business.mapper.PlatformUserMapper;
import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * AuthService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC 类型；参数数量固定，值不拼接进
 * SQL。
 */
@Repository
public class AuthRepository {
  private final SqlExecutor s;
  private final PlatformUserMapper users;

  public AuthRepository(SqlExecutor s, PlatformUserMapper users) {
    this.s = s;
    this.users = users;
  }

  /** 执行参数绑定的写入并返回影响行数：initializeLoginLimit。锁和条件更新在调用方业务事务中生效。 */
  public int initializeLoginLimit(Object id, Object windowStart) {
    return s.run("INSERT IGNORE INTO p0_login_limits VALUES(?,0,?)", id, windowStart);
  }

  /** 执行参数绑定的写入并返回影响行数：resetExpiredLoginLimit。锁和条件更新在调用方业务事务中生效。 */
  public int resetExpiredLoginLimit(Object windowStart, Object k, Object windowStart3) {
    return s.run(
        "UPDATE p0_login_limits SET failures=0,window_start=? WHERE id=? AND window_start<?",
        windowStart,
        k,
        windowStart3);
  }

  /** 读取聚合数值：loginFailures。锁和条件更新在调用方业务事务中生效。 */
  public long loginFailures(Object k) {
    return s.count("SELECT failures FROM p0_login_limits WHERE id=?", k);
  }

  /** 执行参数绑定的写入并返回影响行数：incrementLoginFailures。锁和条件更新在调用方业务事务中生效。 */
  public int incrementLoginFailures(Object k) {
    return s.run("UPDATE p0_login_limits SET failures=failures+1 WHERE id=?", k);
  }

  /** 执行参数绑定的写入并返回影响行数：clearLoginFailures。锁和条件更新在调用方业务事务中生效。 */
  public int clearLoginFailures(Object key) {
    return s.run("UPDATE p0_login_limits SET failures=0 WHERE id=?", key);
  }

  /** 执行参数绑定的写入并返回影响行数：insertSession。锁和条件更新在调用方业务事务中生效。 */
  public int insertSession(
      Object id,
      Object userId,
      Object accessHash,
      Object refreshHash,
      Object csrfHash,
      Object accessExpires,
      Object expiresAt) {
    return s.run(
        "INSERT INTO p0_sessions VALUES(?,?,?,?,?,?,?,FALSE)",
        id,
        userId,
        accessHash,
        refreshHash,
        csrfHash,
        accessExpires,
        expiresAt);
  }

  /** 读取记录列表：lockRefreshSession。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> lockRefreshSession(Object refreshHash, Object expiresAt) {
    return s.list(
        "SELECT ss.* FROM p0_sessions ss JOIN p0_users u ON u.id=ss.user_id WHERE"
            + " refresh_hash=? AND ss.revoked=FALSE AND ss.expires_at>? AND u.enabled=TRUE FOR"
            + " UPDATE",
        refreshHash,
        expiresAt);
  }

  /** 执行参数绑定的写入并返回影响行数：revokeSession。锁和条件更新在调用方业务事务中生效。 */
  public int revokeSession(Object id) {
    return s.run("UPDATE p0_sessions SET revoked=TRUE WHERE id=?", id);
  }

  /** 读取记录列表：findAccessSession。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findAccessSession(
      Object accessHash, Object accessExpires, Object expiresAt) {
    return s.list(
        "SELECT u.id,u.role,ss.id AS session_id FROM p0_sessions ss JOIN p0_users u ON"
            + " u.id=ss.user_id WHERE ss.access_hash=? AND ss.access_expires>? AND"
            + " ss.expires_at>? AND ss.revoked=FALSE AND u.enabled=TRUE",
        accessHash,
        accessExpires,
        expiresAt);
  }

  /** 读取记录列表：findRefreshSession。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> findRefreshSession(Object refreshHash) {
    return s.list("SELECT * FROM p0_sessions WHERE refresh_hash=?", refreshHash);
  }

  /** 执行参数绑定的写入并返回影响行数：revokeRefreshSession。锁和条件更新在调用方业务事务中生效。 */
  public int revokeRefreshSession(Object refreshHash) {
    return s.run("UPDATE p0_sessions SET revoked=TRUE WHERE refresh_hash=?", refreshHash);
  }

  /** 执行参数绑定的写入并返回影响行数：updatePassword。锁和条件更新在调用方业务事务中生效。 */
  public int updatePassword(Object passwordHash, Object userId) {
    return s.run("UPDATE p0_users SET password_hash=? WHERE id=?", passwordHash, userId);
  }

  /** 执行参数绑定的写入并返回影响行数：revokeUserSessions。锁和条件更新在调用方业务事务中生效。 */
  public int revokeUserSessions(Object userId) {
    return s.run("UPDATE p0_sessions SET revoked=TRUE WHERE user_id=?", userId);
  }

  /** 执行参数绑定的写入并返回影响行数：updateNickname。锁和条件更新在调用方业务事务中生效。 */
  public int updateNickname(Object nickname, Object userId) {
    return s.run("UPDATE p0_users SET nickname=? WHERE id=?", nickname, userId);
  }

  /** 执行参数绑定的写入并返回影响行数：updatePreferences。锁和条件更新在调用方业务事务中生效。 */
  public int updatePreferences(Object preferencesEnabled, Object userId) {
    return s.run(
        "UPDATE p0_users SET preferences_enabled=? WHERE id=?", preferencesEnabled, userId);
  }

  public long countUsername(String username) {
    return users.selectCount(
        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PlatformUser>()
            .eq("username", username));
  }

  public PlatformUser findUserByUsername(String username) {
    return users.selectOne(
        new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PlatformUser>()
            .eq("username", username));
  }

  public PlatformUser findUser(String id) {
    return users.selectById(id);
  }

  public int insertUser(PlatformUser user) {
    return users.insert(user);
  }
}
