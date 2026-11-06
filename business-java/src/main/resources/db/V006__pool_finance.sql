CREATE TABLE IF NOT EXISTS app_host_finance (
 host_id VARCHAR(64) PRIMARY KEY, monthly_cost_cents BIGINT NOT NULL,
 updated_at DATETIME(3) NOT NULL
);
