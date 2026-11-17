ALTER TABLE projects ADD COLUMN delivery_config_hash VARCHAR(64);
ALTER TABLE projects ADD COLUMN registry_push_connection VARCHAR(63);
ALTER TABLE projects ADD COLUMN registry_pull_connection VARCHAR(63);
ALTER TABLE projects ADD COLUMN requested_artifact_tier VARCHAR(20);
ALTER TABLE ci_runs ADD COLUMN delivery_config_hash VARCHAR(64);
ALTER TABLE ci_runs ADD COLUMN external_registry BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ci_runs ADD COLUMN registry_secrets_cleaned BOOLEAN NOT NULL DEFAULT false;
