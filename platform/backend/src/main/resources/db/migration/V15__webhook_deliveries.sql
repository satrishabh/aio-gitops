-- Inbox of GitHub webhook deliveries, keyed by X-GitHub-Delivery, so that a delivery replayed by the Azure webhook
-- buffer or redelivered from GitHub starts at most one build. A replay gets the first attempt's reply.
-- Rows older than app.webhooks.inbox-retention (default 14 days) are deleted daily.
CREATE TABLE webhook_deliveries (
    id             BIGSERIAL    PRIMARY KEY,
    project_id     BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    delivery_id    VARCHAR(100) NOT NULL,
    event          VARCHAR(50),
    payload_sha256 VARCHAR(64)  NOT NULL,
    -- PROCESSING while the first attempt runs; DONE once its reply is recorded
    status         VARCHAR(20)  NOT NULL,
    http_status    INTEGER,
    response       VARCHAR(200),
    run_id         BIGINT,
    attempts       INTEGER      NOT NULL DEFAULT 1,
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT webhook_deliveries_project_delivery_uq UNIQUE (project_id, delivery_id)
);
CREATE INDEX webhook_deliveries_received_idx ON webhook_deliveries (received_at);
