package com.cloudagent.business.repository;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 预览仅保存待确认动作，不产生付款或资源操作；确认通过用户归属和单次状态锁串行执行。 */
@Repository
public class AssistantActionRepository {
  private final SqlExecutor s;

  public AssistantActionRepository(SqlExecutor s) {
    this.s = s;
  }

  public void insert(
      String id,
      String user,
      String action,
      String payload,
      String preview,
      Timestamp end,
      Timestamp now) {
    s.run(
        "INSERT INTO"
            + " p1_action_previews(id,user_id,action,payload,preview,state,expires_at,created_at)"
            + " VALUES(?,?,?,?,?,'PENDING',?,?)",
        id,
        user,
        action,
        payload,
        preview,
        end,
        now);
  }

  public Map<String, Object> lock(String id, String user) {
    return s.one("SELECT * FROM p1_action_previews WHERE id=? AND user_id=? FOR UPDATE", id, user);
  }

  public void done(String id, String result) {
    s.run("UPDATE p1_action_previews SET state='CONFIRMED',result=? WHERE id=?", result, id);
  }

  public List<Map<String, Object>> list(String user) {
    return s.list(
        "SELECT * FROM p1_action_previews WHERE user_id=? ORDER BY created_at DESC LIMIT 50", user);
  }
}
