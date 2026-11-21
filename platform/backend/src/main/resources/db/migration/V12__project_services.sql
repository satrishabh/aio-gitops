-- Project services: a connector used by one project in one environment (Project -> Services).
-- Git (services: in apps/<app>/values-<env>.yaml) is authoritative; this records who attached what, with which
-- options, for the UI, Export and the "used by" check before a connector is disabled.
CREATE TABLE project_services (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connector_id VARCHAR(42)  NOT NULL,
    env          VARCHAR(20)  NOT NULL,
    options      TEXT         NOT NULL DEFAULT '{}',   -- JSON object: option id -> value (strings)
    status       VARCHAR(20)  NOT NULL,                -- ATTACHED | FAILED
    detail       TEXT,
    copied_secret VARCHAR(253),                        -- scope shared: Secret the platform copied into the env namespace
    created_by   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT project_services_unique UNIQUE (project_id, connector_id, env)
);
CREATE INDEX project_services_connector ON project_services (connector_id);
