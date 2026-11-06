package com.cloudagent.business.repository;

import java.util.*;
import org.springframework.stereotype.Repository;

/** 平台资产、具体实例预留和租约；行锁均参与调用方事务。 */
@Repository
public class PoolRepository {
  public void adoptWindows(String id,String capabilities){s.run("UPDATE app_hosts SET pool_state='MANAGED_STATIC',capabilities=?,last_seen_at=UTC_TIMESTAMP(3) WHERE id=? AND pool_state='PREPARING'",capabilities,id);}
  private final SqlExecutor s;

  public PoolRepository(SqlExecutor s) {
    this.s = s;
  }

  public List<Map<String, Object>> hosts() {
    return s.list(
        "SELECT"
            + " id,provider_instance_id,sku_id,name,endpoint,mode,pool_state,generation,current_instance_id,last_seen_at,procurement_expires_at"
            + " FROM app_hosts ORDER BY created_at DESC LIMIT 200");
  }

  public Map<String, Object> host(String id) {
    return s.one("SELECT * FROM app_hosts WHERE id=? FOR UPDATE", id);
  }

  public Map<String, Object> sku(String id) {
    return s.one("SELECT * FROM p0_skus WHERE id=? FOR UPDATE", id);
  }

  public Map<String, Object> skuInfo(String id) {
    return s.one("SELECT * FROM p0_skus WHERE id=?", id);
  }

  public Map<String, Object> capabilitiesView(String id) {
    return s.one("SELECT capabilities FROM app_hosts WHERE id=?", id);
  }

  public void insertHost(String id, Object... values) {
    s.run(
        "INSERT INTO"
            + " app_hosts(id,provider_instance_id,sku_id,name,endpoint,mode,procurement_expires_at,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        values[0],
        values[1],
        values[2],
        values[3],
        values[4],
        values[5],
        values[6]);
  }

  public void token(String hash, String host, Object expires) {
    s.run(
        "INSERT INTO app_registration_tokens(token_hash,host_id,expires_at) VALUES(?,?,?)",
        hash,
        host,
        expires);
  }

  public Map<String, Object> token(String hash) {
    return s.one("SELECT * FROM app_registration_tokens WHERE token_hash=? FOR UPDATE", hash);
  }

  public void consume(String hash) {
    s.run(
        "UPDATE app_registration_tokens SET consumed_at=UTC_TIMESTAMP(3) WHERE token_hash=?", hash);
  }

  public void register(String host, String hash, String capabilities) {
    s.run(
        "UPDATE app_hosts SET"
            + " runner_hash=?,capabilities=?,last_seen_at=UTC_TIMESTAMP(3),pool_state='IDLE' WHERE"
            + " id=? AND pool_state='PREPARING'",
        hash,
        capabilities,
        host);
  }

  public Map<String, Object> runner(String hash) {
    return s.one(
        "SELECT * FROM app_hosts WHERE runner_hash=? AND pool_state<>'RETIRED' FOR UPDATE", hash);
  }

  public void heartbeat(String id) {
    s.run("UPDATE app_hosts SET last_seen_at=UTC_TIMESTAMP(3) WHERE id=?", id);
  }

  public void capabilities(String id, String value) {
    s.run("UPDATE app_hosts SET capabilities=? WHERE id=?", value, id);
  }

  public void revoke(String id) {
    s.run("UPDATE app_hosts SET pool_state='RETIRED',runner_hash=NULL WHERE id=?", id);
  }

  public List<Map<String, Object>> executions(String host) {
    return s.list(
        "SELECT id,kind,state,attempt,result,created_at FROM app_deployments WHERE host_id=? ORDER"
            + " BY created_at DESC LIMIT 30",
        host);
  }

  public Map<String, Object> execution(String id) {
    return s.one("SELECT * FROM app_deployments WHERE id=? FOR UPDATE", id);
  }

  public void retryCleanup(Map<String, Object> h, String task) {
    s.run(
        "UPDATE app_hosts SET pool_state='CLEANING' WHERE id=? AND pool_state='QUARANTINED'",
        h.get("id"));
    s.run(
        "UPDATE app_deployments SET state='QUEUED',result=NULL,updated_at=UTC_TIMESTAMP(3) WHERE"
            + " id=? AND kind='CLEANUP' AND state='FAILED'",
        task);
    s.run(
        "UPDATE p0_tasks SET state='PENDING',attempts=0,next_run=UTC_TIMESTAMP(3) WHERE"
            + " instance_id=? AND state='MANUAL_REQUIRED'",
        h.get("current_instance_id"));
  }

  public void finance(String id, long cents, Object expires) {
    s.run("UPDATE app_hosts SET procurement_expires_at=? WHERE id=?", expires, id);
    s.run(
        "INSERT INTO app_host_finance VALUES(?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE"
            + " monthly_cost_cents=VALUES(monthly_cost_cents),updated_at=VALUES(updated_at)",
        id,
        cents);
  }

  public long leasesBeyond(String id, Object until) {
    return s.count(
        "SELECT COUNT(*) FROM app_allocations a JOIN p0_instances i ON i.id=a.instance_id WHERE"
            + " a.host_id=? AND a.state IN ('ACTIVE','FROZEN','RECLAIMING') AND"
            + " DATE_ADD(a.expires_at,INTERVAL i.grace_days DAY)>?",
        id,
        until);
  }

  public Object financeReport() {
    return s.list(
        "SELECT h.id,h.name,h.mode,h.pool_state,h.procurement_expires_at,f.monthly_cost_cents,CASE"
            + " WHEN h.procurement_expires_at<DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 7 DAY) THEN TRUE"
            + " ELSE FALSE END AS procurement_expiring FROM app_hosts h LEFT JOIN app_host_finance"
            + " f ON f.host_id=h.id ORDER BY h.created_at DESC LIMIT 200");
  }

  public List<Map<String, Object>> orderItems(String order) {
    return s.list(
        "SELECT i.*,o.sku_id,o.months FROM p0_items i JOIN p0_orders o ON o.id=i.order_id WHERE"
            + " o.id=? ORDER BY i.id",
        order);
  }

  public List<Map<String, Object>> idle(String sku, Object until) {
    return s.list(
        "SELECT * FROM app_hosts WHERE sku_id=? AND pool_state='IDLE' AND"
            + " last_seen_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 90 SECOND) AND"
            + " procurement_expires_at>=? ORDER BY id FOR UPDATE",
        sku,
        until);
  }

  public void reserve(String host, String item) {
    s.run(
        "UPDATE app_hosts SET pool_state='RESERVED',reserved_item_id=? WHERE id=? AND"
            + " pool_state='IDLE'",
        item,
        host);
  }

  public void cancel(String order) {
    s.run(
        "UPDATE app_hosts h JOIN p0_items i ON i.id=h.reserved_item_id SET"
            + " h.pool_state='IDLE',h.reserved_item_id=NULL WHERE i.order_id=? AND"
            + " h.pool_state='RESERVED'",
        order);
  }

  public List<Map<String, Object>> reservation(String instance) {
    return s.list(
        "SELECT h.*,i.id AS item_id,i.order_id,i.user_id FROM app_hosts h JOIN p0_items i ON"
            + " i.id=h.reserved_item_id WHERE i.instance_id=? FOR UPDATE",
        instance);
  }

  public List<Map<String, Object>> allocation(String instance) {
    return s.list(
        "SELECT a.*,h.endpoint,h.provider_instance_id,h.mode,h.procurement_expires_at,h.pool_state"
            + " FROM app_allocations a JOIN app_hosts h ON h.id=a.host_id WHERE a.instance_id=? FOR"
            + " UPDATE",
        instance);
  }

  public void allocate(
      String id, Map<String, Object> h, String instance, Object expires, Object created) {
    s.run(
        "UPDATE app_hosts SET pool_state='ALLOCATED',current_instance_id=?,generation=generation+1"
            + " WHERE id=?",
        instance,
        h.get("id"));
    s.run(
        "INSERT INTO"
            + " app_allocations(id,host_id,user_id,item_id,order_id,instance_id,generation,state,expires_at,created_at)"
            + " SELECT ?,id,?,?,?,?,generation,'ACTIVE',?,? FROM app_hosts WHERE id=?",
        id,
        h.get("user_id"),
        h.get("item_id"),
        h.get("order_id"),
        instance,
        expires,
        created,
        h.get("id"));
  }

  public void extend(String instance, Object expires) {
    s.run("UPDATE app_allocations SET expires_at=? WHERE instance_id=?", expires, instance);
  }

  public void freeze(String instance, String state) {
    s.run(
        "UPDATE app_allocations SET state=? WHERE instance_id=? AND state IN ('ACTIVE','FROZEN')",
        state,
        instance);
  }

  public void reclaim(String instance) {
    s.run(
        "UPDATE app_deployments d JOIN app_allocations a ON a.id=d.allocation_id SET"
            + " d.state='CANCELLED' WHERE a.instance_id=? AND d.state='QUEUED' AND"
            + " d.kind<>'CLEANUP'",
        instance);
    s.run(
        "UPDATE app_hosts SET pool_state='CLEANING',generation=generation+1 WHERE"
            + " current_instance_id=? AND pool_state IN ('ALLOCATED','FROZEN')",
        instance);
    s.run(
        "UPDATE app_allocations SET state='RECLAIMING' WHERE instance_id=? AND state IN"
            + " ('ACTIVE','FROZEN')",
        instance);
  }

  public void refreshCapacity(String sku) {
    s.run(
        "UPDATE p0_skus SET capacity=reserved+allocated+(SELECT COUNT(*) FROM app_hosts WHERE"
            + " sku_id=? AND pool_state='IDLE' AND last_seen_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL"
            + " 90 SECOND)) WHERE id=?",
        sku,
        sku);
  }

  public void instanceMetadata(String instance, String address) {
    s.run("UPDATE p0_instances SET public_ip=?,simulated=FALSE WHERE id=?", address, instance);
  }

  public boolean pooledSku(String sku) {
    return s.count("SELECT COUNT(*) FROM p0_skus WHERE id=? AND adapter='POOL'", sku) > 0;
  }

  public Map<String, Object> instance(String id) {
    return s.one("SELECT * FROM p0_instances WHERE id=?", id);
  }

  public long running(String host) {
    return s.count(
        "SELECT COUNT(*) FROM app_deployments WHERE host_id=? AND state IN"
            + " ('RUNNING','MANUAL_REQUIRED')",
        host);
  }

  public List<Map<String, Object>> command(String key) {
    return s.list("SELECT * FROM app_deployments WHERE id=?", key);
  }

  public void command(
      String key, Map<String, Object> a, Map<String, Object> h, String kind, String payload) {
    s.run(
        "INSERT IGNORE INTO"
            + " app_deployments(id,allocation_id,host_id,generation,kind,state,payload,user_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,'QUEUED',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
        key,
        a.get("id"),
        h.get("id"),
        h.get("generation"),
        kind,
        payload,
        a.get("user_id"));
  }

  public List<Map<String, Object>> allocations(String user) {
    return s.list(
        "SELECT a.*,h.name AS host_name,h.endpoint,h.mode FROM app_allocations a JOIN app_hosts h"
            + " ON h.id=a.host_id WHERE a.user_id=? ORDER BY a.created_at DESC LIMIT 100",
        user);
  }
}
