package com.cloudagent.business.repository;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 联系方式、单次挑战与持久化通知；验证码只在挑战表保存摘要，沙箱投递箱有独立访问权限和失效时间。 */
@Repository
public class DeliveryRepository {
  private final SqlExecutor s;

  public DeliveryRepository(SqlExecutor s) {
    this.s = s;
  }

  public void lockUser(String user) {
    s.one("SELECT id FROM p0_users WHERE id=? AND enabled=TRUE FOR UPDATE", user);
  }

  public Map<String, Object> lockRequestLimit(String key, Timestamp now, Timestamp cutoff) {
    s.run("INSERT IGNORE INTO p0_login_limits VALUES(?,0,?)", key, now);
    s.run(
        "UPDATE p0_login_limits SET failures=0,window_start=? WHERE id=? AND window_start<?",
        now,
        key,
        cutoff);
    return s.one("SELECT failures FROM p0_login_limits WHERE id=? FOR UPDATE", key);
  }

  public void countRequest(String key) {
    s.run("UPDATE p0_login_limits SET failures=failures+1 WHERE id=?", key);
  }

  public List<Map<String, Object>> contact(String user) {
    return s.list("SELECT * FROM p1_contacts WHERE user_id=?", user);
  }

  public List<Map<String, Object>> findContact(String channel, String destination) {
    return s.list(
        "SELECT c.* FROM p1_contacts c JOIN p0_users u ON u.id=c.user_id WHERE c.channel=? AND"
            + " c.destination=? AND u.enabled=TRUE",
        channel,
        destination);
  }

  public long recent(String destination, Timestamp after) {
    return s.count(
        "SELECT COUNT(*) FROM p1_challenges WHERE destination=? AND created_at>?",
        destination,
        after);
  }

  public void challenge(
      String id,
      String user,
      String purpose,
      String channel,
      String destination,
      String hash,
      Timestamp expires,
      Timestamp now) {
    s.run(
        "INSERT INTO"
            + " p1_challenges(id,user_id,purpose,channel,destination,code_hash,expires_at,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        user,
        purpose,
        channel,
        destination,
        hash,
        expires,
        now);
  }

  public Map<String, Object> lockChallenge(String id) {
    return s.one("SELECT * FROM p1_challenges WHERE id=? FOR UPDATE", id);
  }

  public Map<String, Object> findChallenge(String id) {
    return s.one("SELECT * FROM p1_challenges WHERE id=?", id);
  }

  public void attempt(String id) {
    s.run("UPDATE p1_challenges SET attempts=attempts+1 WHERE id=?", id);
  }

  public void consume(String id) {
    s.run("UPDATE p1_challenges SET consumed=TRUE WHERE id=?", id);
  }

  public void bind(String user, String channel, String destination, Timestamp now) {
    s.run("DELETE FROM p1_contacts WHERE user_id=?", user);
    s.run("INSERT INTO p1_contacts VALUES(?,?,?,?)", user, channel, destination, now);
  }

  public void reset(String user, String hash) {
    s.run("UPDATE p0_users SET password_hash=? WHERE id=?", hash, user);
    s.run("UPDATE p0_sessions SET revoked=TRUE WHERE user_id=?", user);
    s.run("UPDATE p1_challenges SET consumed=TRUE WHERE user_id=? AND purpose='RESET'", user);
  }

  public void enqueue(
      String id,
      String key,
      String user,
      String channel,
      String destination,
      String subject,
      String content,
      Timestamp now) {
    s.run(
        "INSERT IGNORE INTO"
            + " p1_deliveries(id,event_key,user_id,channel,destination,subject,content,state,next_run,created_at)"
            + " VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",
        id,
        key,
        user,
        channel,
        destination,
        subject,
        content,
        now,
        now);
  }

  public List<Map<String, Object>> lockDue(Timestamp now) {
    return s.list(
        "SELECT * FROM p1_deliveries WHERE state IN ('PENDING','RUNNING') AND next_run<=? ORDER BY"
            + " next_run LIMIT 1 FOR UPDATE",
        now);
  }

  public void lease(String id, Timestamp next) {
    s.run("UPDATE p1_deliveries SET state='RUNNING',next_run=? WHERE id=?", next, id);
  }

  public void result(String id, String state, Timestamp next, String error) {
    s.run(
        "UPDATE p1_deliveries SET state=?,attempts=attempts+1,next_run=?,error=? WHERE id=?",
        state,
        next,
        error,
        id);
  }

  public void receipt(
      String id,
      String channel,
      String destination,
      String subject,
      String content,
      Timestamp expiry) {
    s.run(
        "INSERT IGNORE INTO p1_delivery_receipts VALUES(?,?,?,?,?,'LOCAL',?)",
        id,
        channel,
        destination,
        subject,
        content,
        expiry);
  }

  public List<Map<String, Object>> receipts(Timestamp now) {
    return s.list(
        "SELECT * FROM p1_delivery_receipts WHERE expires_at>? ORDER BY expires_at DESC LIMIT 100",
        now);
  }

  public List<Map<String, Object>> deliveries() {
    return s.list(
        "SELECT id,event_key,user_id,channel,state,attempts,error,created_at FROM p1_deliveries"
            + " ORDER BY created_at DESC LIMIT 200");
  }

  public Map<String, Object> delivery(String id) {
    return s.one("SELECT * FROM p1_deliveries WHERE id=? FOR UPDATE", id);
  }

  public void retry(String id, Timestamp now) {
    s.run(
        "UPDATE p1_deliveries SET state='PENDING',attempts=0,next_run=?,error=NULL WHERE id=?",
        now,
        id);
  }

  public void redactExpired(Timestamp now) {
    s.run("UPDATE p1_deliveries SET content='[验证码已过期]' WHERE subject='验证码' AND created_at<?", now);
    s.run("DELETE FROM p1_delivery_receipts WHERE expires_at<?", now);
  }
}
