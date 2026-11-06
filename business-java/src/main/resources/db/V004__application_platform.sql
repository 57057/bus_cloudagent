CREATE TABLE IF NOT EXISTS app_hosts (
 id VARCHAR(64) PRIMARY KEY, provider_instance_id VARCHAR(128) NOT NULL UNIQUE,
 sku_id VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL, endpoint VARCHAR(255) NOT NULL,
 mode VARCHAR(20) NOT NULL, pool_state VARCHAR(24) NOT NULL DEFAULT 'PREPARING',
 runner_hash VARCHAR(64), generation BIGINT NOT NULL DEFAULT 0,
 current_instance_id VARCHAR(64) UNIQUE, reserved_item_id VARCHAR(64) UNIQUE,
 last_seen_at DATETIME(3), capabilities JSON, procurement_expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL, INDEX(sku_id,pool_state)
);
CREATE TABLE IF NOT EXISTS app_registration_tokens (
 token_hash VARCHAR(64) PRIMARY KEY, host_id VARCHAR(64) NOT NULL,
 expires_at DATETIME(3) NOT NULL, consumed_at DATETIME(3)
);
CREATE TABLE IF NOT EXISTS app_allocations (
 id VARCHAR(64) PRIMARY KEY, host_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL,
 item_id VARCHAR(64) NOT NULL UNIQUE, order_id VARCHAR(64) NOT NULL,
 instance_id VARCHAR(64) NOT NULL UNIQUE, generation BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL, expires_at DATETIME(3) NOT NULL, created_at DATETIME(3) NOT NULL,
 INDEX(user_id,state), INDEX(host_id)
);
CREATE TABLE IF NOT EXISTS app_projects (
 id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, name VARCHAR(100) NOT NULL,
 allocation_id VARCHAR(64) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 current_deployment_id VARCHAR(64), version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL, INDEX(user_id,created_at)
);
CREATE TABLE IF NOT EXISTS app_plans (
 id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(64) NOT NULL, project_id VARCHAR(64) NOT NULL,
 kind VARCHAR(20) NOT NULL, snapshot JSON NOT NULL, plan_hash VARCHAR(64) NOT NULL,
 base_version BIGINT NOT NULL, state VARCHAR(20) NOT NULL, expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS app_deployments (
 id VARCHAR(64) PRIMARY KEY, plan_id VARCHAR(64) UNIQUE, project_id VARCHAR(64),
 allocation_id VARCHAR(64), host_id VARCHAR(64) NOT NULL, generation BIGINT NOT NULL,
 kind VARCHAR(20) NOT NULL, state VARCHAR(24) NOT NULL, payload JSON NOT NULL,
 idempotency_key VARCHAR(100), user_id VARCHAR(64), attempt BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(3), result JSON, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 UNIQUE(user_id,idempotency_key), INDEX(host_id,state)
);
CREATE TABLE IF NOT EXISTS app_task_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, deployment_id VARCHAR(64) NOT NULL,
 attempt BIGINT NOT NULL, sequence_no BIGINT NOT NULL, message VARCHAR(2000) NOT NULL,
 created_at DATETIME(3) NOT NULL, UNIQUE(deployment_id,attempt,sequence_no)
);
CREATE TABLE IF NOT EXISTS app_diagnoses (
 id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL, user_id VARCHAR(64) NOT NULL,
 deployment_id VARCHAR(64), evidence JSON NOT NULL, conclusion JSON NOT NULL,
 created_at DATETIME(3) NOT NULL
);
