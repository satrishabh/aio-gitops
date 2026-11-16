-- Text written by scheduled assistant jobs (G6), e.g. the daily delivery summary; one per kind and day.
CREATE TABLE assistant_reports (
    id         BIGSERIAL PRIMARY KEY,
    kind       VARCHAR(40)  NOT NULL,
    day        DATE         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    model      VARCHAR(120),
    text       TEXT,
    basis      VARCHAR(300),
    CONSTRAINT uq_assistant_reports_kind_day UNIQUE (kind, day)
);
-- the daily summary reads the last 24 hours of deployments
CREATE INDEX IF NOT EXISTS ix_promotions_requested_at ON promotions(requested_at);
