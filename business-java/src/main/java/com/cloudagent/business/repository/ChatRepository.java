package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/**
 * ChatService的数据访问：仅封装固定 SQL/受控标识符，不负责业务权限、状态决策或事务编排。 Object 参数用于兼容现有 Map 行的 JDBC 类型；参数数量固定，值不拼接进
 * SQL。
 */
@Repository
public class ChatRepository {
  private final SqlExecutor s;

  public ChatRepository(SqlExecutor s) {
    this.s = s;
  }

  /** 读取聚合数值：databasePing。锁和条件更新在调用方业务事务中生效。 */
  public long databasePing() {
    return s.count("SELECT 1");
  }

  /** 执行参数绑定的写入并返回影响行数：insertSession。锁和条件更新在调用方业务事务中生效。 */
  public int insertSession(Object id, Object userId, Object title, Object createdAt) {
    return s.run(
        "INSERT INTO p0_chat_sessions VALUES(?,?,?,FALSE,?)", id, userId, title, createdAt);
  }

  /** 读取单条记录（不存在时抛出404）：findSession。锁和条件更新在调用方业务事务中生效。 */
  public Map<String, Object> findSession(Object id) {
    return s.one("SELECT * FROM p0_chat_sessions WHERE id=?", id);
  }

  /** 读取记录列表：listUserSessions。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listUserSessions(Object userId) {
    return s.list(
        "SELECT * FROM p0_chat_sessions WHERE user_id=? AND deleted=FALSE ORDER BY created_at"
            + " DESC LIMIT 100",
        userId);
  }

  /** 读取记录列表：listMessages。锁和条件更新在调用方业务事务中生效。 */
  public List<Map<String, Object>> listMessages(Object id) {
    return s.list(
        "SELECT role,content,created_at FROM p0_chat_messages WHERE session_id=? ORDER BY"
            + " created_at,id",
        id);
  }

  /** 执行参数绑定的写入并返回影响行数：markSessionDeleted。锁和条件更新在调用方业务事务中生效。 */
  public int markSessionDeleted(Object id, Object userId) {
    return s.run("UPDATE p0_chat_sessions SET deleted=TRUE WHERE id=? AND user_id=?", id, userId);
  }

  /** 执行参数绑定的写入并返回影响行数：deleteMessages。锁和条件更新在调用方业务事务中生效。 */
  public int deleteMessages(Object id) {
    return s.run("DELETE FROM p0_chat_messages WHERE session_id=?", id);
  }

  /** 执行参数绑定的写入并返回影响行数：insertUserMessage。锁和条件更新在调用方业务事务中生效。 */
  public int insertUserMessage(
      Object id, Object sessionId, Object role, Object content, Object createdAt) {
    return s.run(
        "INSERT INTO p0_chat_messages VALUES(?,?,?,?,?)", id, sessionId, role, content, createdAt);
  }

  /** 读取聚合数值：countActiveSessions。锁和条件更新在调用方业务事务中生效。 */
  public long countActiveSessions(Object session) {
    return s.count("SELECT COUNT(*) FROM p0_chat_sessions WHERE id=? AND deleted=FALSE", session);
  }

  /** 执行参数绑定的写入并返回影响行数：insertAssistantMessage。锁和条件更新在调用方业务事务中生效。 */
  public int insertAssistantMessage(
      Object id, Object sessionId, Object role, Object content, Object createdAt) {
    return s.run(
        "INSERT INTO p0_chat_messages VALUES(?,?,?,?,?)", id, sessionId, role, content, createdAt);
  }
}
