-- 追加控制协议表，不修改已部署迁移；CREATE IF NOT EXISTS支持中断后的安全重试。
CREATE TABLE IF NOT EXISTS app_deployment_controls (
 deployment_id VARCHAR(64) PRIMARY KEY,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 commit_started BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(3) NOT NULL
);
CREATE TABLE IF NOT EXISTS app_project_runtime (
 project_id VARCHAR(64) PRIMARY KEY,
 runtime_state VARCHAR(24) NOT NULL,
 observed_at DATETIME(3) NOT NULL
);
