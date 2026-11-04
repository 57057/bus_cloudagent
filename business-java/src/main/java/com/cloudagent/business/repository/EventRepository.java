package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/** 审计、通知模板和事务 outbox 的数据访问；不创建独立事务。 */
@Repository
public class EventRepository {
  private final SqlExecutor s;

  public EventRepository(SqlExecutor s) {
    this.s = s;
  }

  public int insertAudit(
      String id,
      String actorId,
      String action,
      String objectId,
      String detail,
      String requestId,
      java.sql.Timestamp createdAt) {
    return s.run(
        "INSERT INTO p0_audits VALUES(?,?,?,?,?,?,?)",
        id,
        actorId,
        action,
        objectId,
        detail,
        requestId,
        createdAt);
  }

  public int insertEvent(
      String id, String key, String type, String payload, java.sql.Timestamp createdAt) {
    return s.run(
        "INSERT IGNORE INTO p0_outbox VALUES(?,?,?,?,?,?,?)",
        id,
        key,
        type,
        payload,
        "PENDING",
        0,
        createdAt);
  }

  public List<Map<String, Object>> findTemplates(String kind) {
    return s.list("SELECT * FROM p0_notification_templates WHERE id=?", kind);
  }
}
