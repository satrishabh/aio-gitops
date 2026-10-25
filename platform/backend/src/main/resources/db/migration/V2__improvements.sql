-- Per-project scan gate, run re-runs / risk overrides, archived step logs, richer env status
ALTER TABLE projects   ADD COLUMN scan_severity   VARCHAR(40) NOT NULL DEFAULT 'HIGH,CRITICAL';

ALTER TABLE ci_runs    ADD COLUMN rerun_of        BIGINT;
ALTER TABLE ci_runs    ADD COLUMN gate_overridden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ci_runs    ADD COLUMN override_reason TEXT;
ALTER TABLE ci_runs    ADD COLUMN scan_severity   VARCHAR(40);
ALTER TABLE ci_runs    ADD COLUMN logs_archived   BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE run_steps  ADD COLUMN log_text        TEXT;

ALTER TABLE env_status ADD COLUMN desired_tag     VARCHAR(200);
ALTER TABLE env_status ADD COLUMN app_url         VARCHAR(500);
ALTER TABLE env_status ADD COLUMN replicas        INT;
