CREATE TABLE app_users (
    id             BIGSERIAL PRIMARY KEY,
    github_login   VARCHAR(100) NOT NULL UNIQUE,
    name           VARCHAR(200),
    avatar_url     VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ
);

CREATE TABLE projects (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL UNIQUE,
    repo_url        VARCHAR(500) NOT NULL,
    repo_owner      VARCHAR(100) NOT NULL,
    repo_name       VARCHAR(100) NOT NULL,
    default_branch  VARCHAR(100) NOT NULL,
    dockerfile      VARCHAR(300) NOT NULL,
    context_dir     VARCHAR(300) NOT NULL,
    test_image      VARCHAR(300) NOT NULL,
    test_command    TEXT,
    image_repo      VARCHAR(500) NOT NULL,
    webhook_secret  VARCHAR(100) NOT NULL,
    webhook_id      BIGINT,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ci_runs (
    id                 BIGSERIAL PRIMARY KEY,
    project_id         BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    pipeline_run_name  VARCHAR(200),
    trigger            VARCHAR(20)  NOT NULL,
    triggered_by       VARCHAR(100) NOT NULL,
    git_ref            VARCHAR(200) NOT NULL,
    commit_sha         VARCHAR(64),
    deploy             BOOLEAN NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    message            TEXT,
    image_url          VARCHAR(500),
    image_digest       VARCHAR(200),
    vuln_critical      INT,
    vuln_high          INT,
    vuln_medium        INT,
    vuln_low           INT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ
);
CREATE INDEX ix_ci_runs_project ON ci_runs(project_id, id DESC);
CREATE INDEX ix_ci_runs_status ON ci_runs(status);

CREATE TABLE run_steps (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES ci_runs(id) ON DELETE CASCADE,
    task_name       VARCHAR(100) NOT NULL,
    step_name       VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    pod_name        VARCHAR(253),
    container_name  VARCHAR(253),
    exit_code       INT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    UNIQUE (run_id, task_name, step_name)
);

CREATE TABLE env_status (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    environment     VARCHAR(20) NOT NULL,
    sync_status     VARCHAR(50),
    health_status   VARCHAR(50),
    image_tag       VARCHAR(200),
    revision        VARCHAR(100),
    updated_at      TIMESTAMPTZ,
    UNIQUE (project_id, environment)
);

CREATE TABLE deployments (
    id               BIGSERIAL PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    environment      VARCHAR(20) NOT NULL,
    argo_history_id  BIGINT NOT NULL,
    revision         VARCHAR(100),
    image_tag        VARCHAR(200),
    deployed_at      TIMESTAMPTZ,
    UNIQUE (project_id, environment, argo_history_id)
);

CREATE TABLE promotions (
    id            BIGSERIAL PRIMARY KEY,
    project_id    BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    from_env      VARCHAR(20),
    to_env        VARCHAR(20) NOT NULL,
    image_tag     VARCHAR(200) NOT NULL,
    kind          VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    requested_by  VARCHAR(100) NOT NULL,
    decided_by    VARCHAR(100),
    message       TEXT,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ
);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(100) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    target      VARCHAR(300),
    details     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_created ON audit_log(id DESC);
