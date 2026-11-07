ALTER TABLE projects ADD COLUMN owner_subject VARCHAR(500);
ALTER TABLE scan_runs ADD COLUMN owner_subject VARCHAR(500);
ALTER TABLE audit_log ADD COLUMN actor_subject VARCHAR(500);

-- Only bind legacy rows when an existing account has a known OIDC subject.
-- Unmapped rows remain admin-only; never claim resources by a username at login.
UPDATE projects p SET owner_subject = u.subject FROM app_users u
WHERE p.created_by = u.login AND u.subject IS NOT NULL;
UPDATE scan_runs s SET owner_subject = u.subject FROM app_users u
WHERE s.triggered_by = u.login AND u.subject IS NOT NULL;
UPDATE audit_log a SET actor_subject = u.subject FROM app_users u
WHERE a.actor = u.login AND u.subject IS NOT NULL;
CREATE INDEX ix_projects_owner_subject ON projects(owner_subject);
CREATE INDEX ix_scan_runs_owner_subject_id ON scan_runs(owner_subject, id DESC);
CREATE INDEX ix_audit_log_actor_subject_id ON audit_log(actor_subject, id DESC);
