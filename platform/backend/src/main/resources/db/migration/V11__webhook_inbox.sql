-- Inbox for GitHub webhook deliveries: a redelivery with the same X-GitHub-Delivery id is not processed twice.
CREATE TABLE webhook_deliveries (
    delivery_id VARCHAR(100) PRIMARY KEY,
    project_id  BIGINT,
    event       VARCHAR(60),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome     VARCHAR(300)
);
CREATE INDEX ix_webhook_deliveries_received ON webhook_deliveries(received_at);
-- same-commit dedup lookup (project, ref, deploy, status)
CREATE INDEX IF NOT EXISTS ix_ci_runs_project_ref ON ci_runs(project_id, git_ref);
