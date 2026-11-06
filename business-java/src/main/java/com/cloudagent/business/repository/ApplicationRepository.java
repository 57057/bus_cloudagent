package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/** 项目和持久化执行协议的数据访问，任务领取采用主机行锁串行化。 */
@Repository
public class ApplicationRepository {
  public Map<String, Object> allocationView(String id, String user) {
    return s.one(
        "SELECT a.*,h.pool_state,h.generation AS host_generation,h.last_seen_at,h.mode AS host_mode,i.state AS instance_state FROM"
            + " app_allocations a JOIN app_hosts h ON h.id=a.host_id JOIN p0_instances i ON"
            + " i.id=a.instance_id WHERE a.id=? AND a.user_id=? AND a.state IN ('ACTIVE','FROZEN')"
            + " AND a.generation=h.generation AND h.current_instance_id=a.instance_id AND i.state"
            + " IN ('RUNNING','STOPPED','SUSPENDED','STARTING','STOPPING','RESTARTING')",
        id,
        user);
  }

  public long projectCount(String allocation) {
    return s.count(
        "SELECT COUNT(*) FROM app_projects WHERE allocation_id=? AND state='ACTIVE'", allocation);
  }

  private final SqlExecutor s;

  public ApplicationRepository(SqlExecutor s) {
    this.s = s;
  }

  public Map<String, Object> allocation(String id, String user) {
    return s.one(
        "SELECT a.*,h.pool_state,h.generation AS host_generation,h.last_seen_at,h.mode AS host_mode,i.state AS instance_state FROM"
            + " app_allocations a JOIN app_hosts h ON h.id=a.host_id JOIN p0_instances i ON"
            + " i.id=a.instance_id WHERE a.id=? AND a.user_id=? AND a.state='ACTIVE' AND"
            + " a.generation=h.generation AND h.current_instance_id=a.instance_id AND"
            + " i.state='RUNNING' AND a.expires_at>UTC_TIMESTAMP(3) FOR UPDATE",
        id,
        user);
  }

  public List<Map<String, Object>> projects(String user) {
    return s.list(
        "SELECT p.*,a.state AS allocation_state,a.expires_at,i.state AS instance_state,h.mode AS host_mode,"
            + " COALESCE(x.runtime_state,CASE WHEN p.current_deployment_id IS NULL THEN 'NOT_DEPLOYED' ELSE 'UNKNOWN' END) AS runtime_state,x.observed_at AS runtime_checked_at"
            + " FROM app_projects p LEFT JOIN app_allocations a ON a.id=p.allocation_id LEFT JOIN p0_instances i ON i.id=a.instance_id"
            + " LEFT JOIN app_hosts h ON h.id=a.host_id LEFT JOIN app_project_runtime x ON x.project_id=p.id"
            + " WHERE p.user_id=? ORDER BY p.created_at DESC LIMIT 100", user);
  }

  public List<Map<String, Object>> plans(String user) {
    return s.list(
        "SELECT p.* FROM app_plans p JOIN app_projects j ON j.id=p.project_id JOIN app_allocations"
            + " a ON a.id=j.allocation_id JOIN app_hosts h ON h.id=a.host_id WHERE p.user_id=? AND"
            + " p.state='READY' AND p.expires_at>UTC_TIMESTAMP(3) AND a.state='ACTIVE' AND"
            + " a.generation=h.generation ORDER BY p.created_at DESC LIMIT 20",
        user);
  }

  public List<Map<String, Object>> recent(String project) {
    return s.list(
        "SELECT t.id,t.kind,t.state,t.result,t.created_at,t.updated_at,COALESCE(c.cancel_requested,FALSE) AS cancel_requested,COALESCE(c.commit_started,FALSE) AS commit_started"
            + " FROM app_deployments t LEFT JOIN app_deployment_controls c ON c.deployment_id=t.id WHERE t.project_id=?"
            + " ORDER BY t.created_at DESC,t.id DESC LIMIT 10",
        project);
  }

  public Map<String, Object> project(String id, String user) {
    return s.one(
        "SELECT p.*,COALESCE(x.runtime_state,CASE WHEN p.current_deployment_id IS NULL THEN 'NOT_DEPLOYED' ELSE 'UNKNOWN' END) AS runtime_state,x.observed_at AS runtime_checked_at"
            + " FROM app_projects p LEFT JOIN app_project_runtime x ON x.project_id=p.id WHERE p.id=? AND p.user_id=? AND p.state='ACTIVE'", id, user);
  }

  public Map<String, Object> lockProject(String id, String user) {
    return s.one(
        "SELECT * FROM app_projects WHERE id=? AND user_id=? AND state='ACTIVE' FOR UPDATE",
        id,
        user);
  }

  public void createProject(String id, String user, String name, String allocation) {
    s.run(
        "INSERT INTO app_projects(id,user_id,name,allocation_id,created_at)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3))",
        id,
        user,
        name,
        allocation);
  }

  public void plan(
      String id,
      String user,
      String project,
      String kind,
      String payload,
      String hash,
      Object version,
      Object expires) {
    s.run(
        "INSERT INTO app_plans VALUES(?,?,?,?,?,?,?, 'READY',?,UTC_TIMESTAMP(3))",
        id,
        user,
        project,
        kind,
        payload,
        hash,
        version,
        expires);
  }

  public Map<String, Object> plan(String id, String user) {
    return s.one("SELECT * FROM app_plans WHERE id=? AND user_id=? FOR UPDATE", id, user);
  }

  public List<Map<String, Object>> byPlan(String plan) {
    return s.list("SELECT * FROM app_deployments WHERE plan_id=?", plan);
  }

  public List<Map<String, Object>> byKey(String user, String key) {
    return s.list("SELECT * FROM app_deployments WHERE user_id=? AND idempotency_key=?", user, key);
  }

  public long busy(String host) {
    return s.count(
        "SELECT COUNT(*) FROM app_deployments WHERE host_id=? AND state IN"
            + " ('QUEUED','RUNNING','MANUAL_REQUIRED')",
        host);
  }

  public void enqueue(
      String id, Map<String, Object> plan, Map<String, Object> allocation, String key) {
    s.run(
        "INSERT INTO"
            + " app_deployments(id,plan_id,project_id,allocation_id,host_id,generation,kind,state,payload,idempotency_key,user_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,'QUEUED',?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        id,
        plan.get("id"),
        plan.get("project_id"),
        allocation.get("id"),
        allocation.get("host_id"),
        allocation.get("generation"),
        plan.get("kind"),
        plan.get("snapshot"),
        key,
        plan.get("user_id"));
    s.run("UPDATE app_plans SET state='CONFIRMED' WHERE id=?", plan.get("id"));
    ensureControl(id);
  }

  public Map<String, Object> deployment(String id, String user) {
    return s.one("SELECT t.*,COALESCE(c.cancel_requested,FALSE) AS cancel_requested,COALESCE(c.commit_started,FALSE) AS commit_started FROM app_deployments t LEFT JOIN app_deployment_controls c ON c.deployment_id=t.id WHERE t.id=? AND t.user_id=?", id, user);
  }

  public Map<String, Object> task(String id) {
    return s.one("SELECT t.*,COALESCE(c.cancel_requested,FALSE) AS cancel_requested,COALESCE(c.commit_started,FALSE) AS commit_started FROM app_deployments t LEFT JOIN app_deployment_controls c ON c.deployment_id=t.id WHERE t.id=? FOR UPDATE", id);
  }

  public void ensureControl(String task) {
    s.run("INSERT IGNORE INTO app_deployment_controls(deployment_id,updated_at) VALUES(?,UTC_TIMESTAMP(3))", task);
  }

  public void requestCancel(String task) {
    ensureControl(task);
    s.run("UPDATE app_deployment_controls SET cancel_requested=TRUE,updated_at=UTC_TIMESTAMP(3) WHERE deployment_id=? AND commit_started=FALSE", task);
  }

  public void beginCommit(String task) {
    ensureControl(task);
    s.run("UPDATE app_deployment_controls SET commit_started=TRUE,updated_at=UTC_TIMESTAMP(3) WHERE deployment_id=? AND cancel_requested=FALSE", task);
  }

  public void cancelled(String task, String result) {
    s.run("UPDATE app_deployments SET state='CANCELLED',result=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?", result, task);
  }

  public void manual(String task, String result) {
    s.run("UPDATE app_deployments SET state='MANUAL_REQUIRED',result=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?", result, task);
  }

  public void dismissPlan(String plan) {
    s.run("UPDATE app_plans SET state='CANCELLED' WHERE id=? AND state='READY'", plan);
  }

  public void runtime(String project, String state) {
    s.run("INSERT INTO app_project_runtime(project_id,runtime_state,observed_at) VALUES(?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE runtime_state=VALUES(runtime_state),observed_at=VALUES(observed_at)", project, state);
  }

  public List<Map<String, Object>> queued(String host) {
    return s.list(
        "SELECT * FROM app_deployments WHERE host_id=? AND state='QUEUED' ORDER BY CASE kind WHEN"
            + " 'CLEANUP' THEN 0 ELSE 1 END,created_at LIMIT 1 FOR UPDATE",
        host);
  }

  public List<Map<String, Object>> running(String host) {
    return s.list(
        "SELECT * FROM app_deployments WHERE host_id=? AND state='RUNNING' FOR UPDATE", host);
  }

  public void claim(String id) {
    s.run(
        "UPDATE app_deployments SET"
            + " state='RUNNING',attempt=attempt+1,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL"
            + " 120 SECOND),updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state='QUEUED'",
        id);
  }

  public void lease(String host) {
    s.run(
        "UPDATE app_deployments SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 120 SECOND)"
            + " WHERE host_id=? AND state='RUNNING'",
        host);
  }

  public void timeout(String host) {
    s.run(
        "UPDATE app_deployments SET state='MANUAL_REQUIRED',updated_at=UTC_TIMESTAMP(3) WHERE"
            + " host_id=? AND state='RUNNING' AND lease_until<UTC_TIMESTAMP(3)",
        host);
  }

  public void reconcileFailed(String id, String result) {
    s.run(
        "UPDATE app_deployments SET state='FAILED',result=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?"
            + " AND state='MANUAL_REQUIRED'",
        result,
        id);
  }

  public void event(String id, long attempt, long sequence, String message) {
    s.run(
        "INSERT IGNORE INTO app_task_events(deployment_id,attempt,sequence_no,message,created_at)"
            + " VALUES(?,?,?,?,UTC_TIMESTAMP(3))",
        id,
        attempt,
        sequence,
        message);
  }

  public List<Map<String, Object>> events(String id, long after) {
    return s.list(
        "SELECT id,attempt,sequence_no,message,created_at FROM app_task_events WHERE"
            + " deployment_id=? AND id>? ORDER BY id LIMIT 200",
        id,
        after);
  }

  public void finish(String id, boolean success, String result) {
    s.run(
        "UPDATE app_deployments SET state=?,result=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
        success ? "SUCCEEDED" : "FAILED",
        result,
        id);
  }

  public void publish(String project, String task) {
    s.run(
        "UPDATE app_projects SET current_deployment_id=?,version=version+1 WHERE id=?",
        task,
        project);
  }

  public void cleanup(Map<String, Object> h, boolean success) {
    if (success) {
      s.run(
          "UPDATE app_allocations SET state='RELEASED' WHERE instance_id=? AND host_id=?",
          h.get("current_instance_id"),
          h.get("id"));
      s.run("UPDATE p0_skus SET allocated=allocated-1 WHERE id=? AND allocated>0", h.get("sku_id"));
      s.run(
          "UPDATE app_hosts SET pool_state='IDLE',current_instance_id=NULL,reserved_item_id=NULL"
              + " WHERE id=?",
          h.get("id"));
    } else s.run("UPDATE app_hosts SET pool_state='QUARANTINED' WHERE id=?", h.get("id"));
  }

  public void diagnose(
      String id,
      String project,
      String user,
      String deployment,
      String evidence,
      String conclusion) {
    s.run(
        "INSERT INTO app_diagnoses VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",
        id,
        project,
        user,
        deployment,
        evidence,
        conclusion);
  }

  public List<Map<String, Object>> diagnoses(String project, String user) {
    return s.list(
        "SELECT * FROM app_diagnoses WHERE project_id=? AND user_id=? ORDER BY created_at DESC"
            + " LIMIT 20",
        project,
        user);
  }

  public List<Map<String, Object>> config(String project) {
    return s.list(
        "SELECT * FROM app_config_versions WHERE project_id=? ORDER BY version DESC LIMIT 1",
        project);
  }

  public Map<String, Object> configById(String id) {
    return s.one("SELECT * FROM app_config_versions WHERE id=?", id);
  }

  public void config(
      String id, String project, long version, String variables, String cipher, String names) {
    s.run(
        "INSERT INTO app_config_versions VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",
        id,
        project,
        version,
        variables,
        cipher,
        names);
    s.run("UPDATE app_projects SET version=version+1 WHERE id=?", project);
  }
}
