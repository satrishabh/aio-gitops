-- Standalone Trivy security scans of any GitHub repository (and optionally an image), independent of CI/CD
CREATE TABLE scan_runs (
    id                 BIGSERIAL PRIMARY KEY,
    project_id         BIGINT REFERENCES projects(id) ON DELETE SET NULL,   -- null: ad-hoc scan of any repo
    repo_url           VARCHAR(500) NOT NULL,
    git_ref            VARCHAR(200) NOT NULL,
    commit_sha         VARCHAR(64),
    image              VARCHAR(500),
    scanners           VARCHAR(100) NOT NULL,       -- comma list: vuln,secret,misconfig,license,image
    fail_on            VARCHAR(40)  NOT NULL DEFAULT 'NONE',
    ignore_unfixed     BOOLEAN      NOT NULL DEFAULT FALSE,
    status             VARCHAR(20)  NOT NULL,
    message            TEXT,
    pipeline_run_name  VARCHAR(200),
    summary            TEXT,                        -- JSON {scanner: {critical,high,medium,low,unknown}}
    findings_count     INT,
    findings_truncated BOOLEAN      NOT NULL DEFAULT FALSE,
    trigger            VARCHAR(20)  NOT NULL,       -- MANUAL | RERUN
    triggered_by       VARCHAR(100) NOT NULL,
    rerun_of           BIGINT,
    logs_archived      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ
);
CREATE INDEX ix_scan_runs_id ON scan_runs(id DESC);
CREATE INDEX ix_scan_runs_status ON scan_runs(status);
CREATE INDEX ix_scan_runs_project ON scan_runs(project_id, id DESC);

CREATE TABLE scan_steps (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE,
    task_name       VARCHAR(100) NOT NULL,
    step_name       VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    pod_name        VARCHAR(253),
    container_name  VARCHAR(253),
    exit_code       INT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    log_text        TEXT,
    UNIQUE (run_id, task_name, step_name)
);

CREATE TABLE scan_findings (
    id                 BIGSERIAL PRIMARY KEY,
    scan_id            BIGINT NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE,
    scanner            VARCHAR(20)  NOT NULL,       -- vuln | secret | misconfig | license | image
    target             VARCHAR(500),                -- file / lockfile / image layer
    rule_id            VARCHAR(200),                -- CVE / GHSA, misconfig check id, secret rule, license name
    pkg                VARCHAR(300),
    installed_version  VARCHAR(200),
    fixed_version      VARCHAR(300),
    severity           VARCHAR(20)  NOT NULL,
    title              TEXT,
    url                VARCHAR(1000),
    location           VARCHAR(100)                 -- line range for secrets / misconfig
);
CREATE INDEX ix_scan_findings_scan ON scan_findings(scan_id, severity);
