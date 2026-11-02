-- Custom domains and path routes: host + path -> one project's environment, with DNS / certificate status
CREATE TABLE domains (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT       NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    env             VARCHAR(20)  NOT NULL,
    host            VARCHAR(253) NOT NULL,
    path            VARCHAR(200) NOT NULL DEFAULT '/',
    strip_prefix    BOOLEAN      NOT NULL DEFAULT FALSE,
    force_https     BOOLEAN      NOT NULL DEFAULT TRUE,
    www_redirect    BOOLEAN      NOT NULL DEFAULT FALSE,
    cert_owner      BOOLEAN      NOT NULL DEFAULT FALSE,   -- this route's Ingress requests the host's certificate
    status          VARCHAR(20)  NOT NULL,                  -- PENDING_DNS | ISSUING | READY | FAILED
    message         TEXT,
    issuer          VARCHAR(63),
    dns_expected    VARCHAR(253),                           -- IP (A record) the host must resolve to
    cert_expires_at TIMESTAMPTZ,
    last_checked_at TIMESTAMPTZ,
    ready_at        TIMESTAMPTZ,
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT domains_host_path UNIQUE (host, path)
);
CREATE INDEX domains_project ON domains (project_id);
CREATE INDEX domains_host ON domains (host);
