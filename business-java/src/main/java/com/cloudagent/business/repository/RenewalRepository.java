package com.cloudagent.business.repository;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Repository;

/** 自动续费授权与周期状态；唯一实例授权和订单业务键使多进程/重启不会重复下单。 */
@Repository
public class RenewalRepository {
  private final SqlExecutor s;

  public RenewalRepository(SqlExecutor s) {
    this.s = s;
  }

  public List<Map<String, Object>> list(String user) {
    return s.list(
        "SELECT * FROM p1_auto_renewals WHERE user_id=? ORDER BY created_at DESC LIMIT 200", user);
  }

  public void save(
      String instance, String user, boolean enabled, int months, long max, Timestamp now) {
    s.run(
        "INSERT INTO"
            + " p1_auto_renewals(instance_id,user_id,enabled,months,max_cents,authorization,state,created_at)"
            + " VALUES(?,?,?,?,?,'SIMULATED','IDLE',?) ON DUPLICATE KEY UPDATE"
            + " enabled=VALUES(enabled),months=VALUES(months),max_cents=VALUES(max_cents),last_cycle=IF(state='FAILED',NULL,last_cycle),state=IF(state='FAILED','IDLE',state),error=NULL",
        instance,
        user,
        enabled,
        months,
        max,
        now);
  }

  public List<Map<String, Object>> due(Timestamp cutoff) {
    return s.list(
        "SELECT a.instance_id FROM p1_auto_renewals a JOIN p0_instances i ON i.id=a.instance_id"
            + " JOIN p0_users u ON u.id=a.user_id WHERE a.enabled=TRUE AND u.enabled=TRUE AND"
            + " i.state IN ('RUNNING','STOPPED','SUSPENDED') AND (i.expires_at<=? OR"
            + " a.state='PROCESSING') ORDER BY a.instance_id LIMIT 100",
        cutoff);
  }

  public Map<String, Object> lock(String instance) {
    return s.one("SELECT * FROM p1_auto_renewals WHERE instance_id=? FOR UPDATE", instance);
  }

  public Map<String, Object> find(String instance) {
    return s.one("SELECT * FROM p1_auto_renewals WHERE instance_id=?", instance);
  }

  public void lockUser(String user) {
    s.one("SELECT id FROM p0_users WHERE id=? AND enabled=TRUE FOR UPDATE", user);
  }

  public Map<String, Object> instance(String id) {
    return s.one("SELECT * FROM p0_instances WHERE id=? FOR UPDATE", id);
  }

  public void cycle(String id, String cycle, String order) {
    s.run(
        "UPDATE p1_auto_renewals SET last_cycle=?,last_order_id=?,state='PROCESSING',error=NULL"
            + " WHERE instance_id=?",
        cycle,
        order,
        id);
  }

  public void state(String id, String state, String error) {
    s.run("UPDATE p1_auto_renewals SET state=?,error=? WHERE instance_id=?", state, error, id);
  }

  public Map<String, Object> payment(String order) {
    return s.one("SELECT * FROM p0_payments WHERE order_id=?", order);
  }
}
