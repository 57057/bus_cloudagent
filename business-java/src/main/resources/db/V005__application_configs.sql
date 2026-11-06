CREATE TABLE IF NOT EXISTS app_config_versions (
 id VARCHAR(64) PRIMARY KEY, project_id VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL, variables JSON NOT NULL, secrets_cipher TEXT NOT NULL,
 secret_names JSON NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE(project_id,version)
);
