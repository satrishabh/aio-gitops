-- Runtime config written into every environment's GitOps values file (env: and resources: blocks)
ALTER TABLE projects ADD COLUMN env_vars       TEXT        NOT NULL DEFAULT '[]';   -- JSON [{"name":..,"value":..}]
ALTER TABLE projects ADD COLUMN cpu_request    VARCHAR(20) NOT NULL DEFAULT '50m';
ALTER TABLE projects ADD COLUMN cpu_limit      VARCHAR(20) NOT NULL DEFAULT '500m';
ALTER TABLE projects ADD COLUMN memory_request VARCHAR(20) NOT NULL DEFAULT '128Mi';
ALTER TABLE projects ADD COLUMN memory_limit   VARCHAR(20) NOT NULL DEFAULT '512Mi';
