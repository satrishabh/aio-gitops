-- Orchestrated sagas (docs/architecture/AIO-V2-ARCHITECTURE.md §3.1)
CREATE TABLE saga_instances (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(60)  NOT NULL,
    business_key    VARCHAR(300) NOT NULL UNIQUE,
    status          VARCHAR(20)  NOT NULL,
    step            INT          NOT NULL DEFAULT 0,
    data            TEXT         NOT NULL DEFAULT '{}',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    deadline_at     TIMESTAMPTZ,
    locked_until    TIMESTAMPTZ,
    last_error      VARCHAR(1000),
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- the worker's only query: open sagas by due time (PAUSED ones are due when their intervention window ends)
CREATE INDEX ix_saga_due ON saga_instances(next_attempt_at) WHERE status IN ('RUNNING', 'COMPENSATING', 'PAUSED');

CREATE TABLE saga_events (
    id      BIGSERIAL PRIMARY KEY,
    saga_id BIGINT NOT NULL REFERENCES saga_instances(id) ON DELETE CASCADE,
    step    VARCHAR(80),
    outcome VARCHAR(30) NOT NULL,
    message VARCHAR(1000),
    at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_saga_events_saga ON saga_events(saga_id, id);

-- Promotions become saga-driven: optimistic locking stops two approvals applying the same request,
-- and the saga link shows progress (APPLYING) instead of a synchronous DONE/FAILED guess.
ALTER TABLE promotions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE promotions ADD COLUMN saga_id BIGINT;
ALTER TABLE promotions ADD COLUMN previous_tag VARCHAR(200);
