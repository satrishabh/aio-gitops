-- Environments and clusters as data (were GitOpsService.ENVS and the static app.clusters settings).
-- Additive: on first start the platform seeds both tables from app.clusters (or the in-cluster fallback) with
-- dev -> staging -> prod exactly as before, so an upgrade changes nothing. Credentials are never stored here:
-- a cluster's token lives only in its Argo CD cluster Secret.
CREATE TABLE clusters (
    name               VARCHAR(63)  PRIMARY KEY,
    cloud              VARCHAR(20)  NOT NULL DEFAULT 'other',
    server             VARCHAR(500) NOT NULL,
    base_domain        VARCHAR(253),
    ingress_ip         VARCHAR(64),
    ingress_class      VARCHAR(63),
    ingress_controller VARCHAR(20),
    ingress_namespace  VARCHAR(63),                           -- namespace of the ingress controller (NetworkPolicy source)
    issuer             VARCHAR(63),
    dns_zone           VARCHAR(253),
    tls                BOOLEAN,
    rollouts           BOOLEAN,                               -- NULL: detect Argo Rollouts on the hub
    status             VARCHAR(20)  NOT NULL DEFAULT 'Unknown', -- Connected | Unreachable | Unknown
    last_check_at      TIMESTAMPTZ,
    last_error         TEXT,
    created_by         VARCHAR(100) NOT NULL DEFAULT 'system',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT clusters_cloud CHECK (cloud IN ('aks', 'gke', 'eks', 'oke', 'doks', 'onprem', 'other'))
);

CREATE TABLE environments (
    name              VARCHAR(20)  PRIMARY KEY,
    position          INT          NOT NULL UNIQUE,           -- display and pipeline order
    promote_from      VARCHAR(20)  REFERENCES environments(name),
    cluster           VARCHAR(63)  NOT NULL REFERENCES clusters(name),
    namespace         VARCHAR(63)  NOT NULL,
    approval_required BOOLEAN      NOT NULL DEFAULT FALSE,    -- promotions/rollbacks wait for an approver; no auto-sync
    spot_allowed      BOOLEAN      NOT NULL DEFAULT FALSE,    -- new apps may opt in to Spot nodes
    prod_like         BOOLEAN      NOT NULL DEFAULT FALSE,    -- production for DORA metrics; never Spot
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX environments_cluster ON environments (cluster);
