-- Existing runs retain their original pipeline-owned deployment behavior.
ALTER TABLE ci_runs ADD COLUMN delivery_mode VARCHAR(20) NOT NULL DEFAULT 'LEGACY';
ALTER TABLE ci_runs ADD COLUMN release_status VARCHAR(30);
ALTER TABLE ci_runs ADD COLUMN release_message VARCHAR(500);
ALTER TABLE ci_runs ADD COLUMN release_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE ci_runs ADD COLUMN release_next_attempt TIMESTAMPTZ;
ALTER TABLE ci_runs ADD COLUMN release_image_repo VARCHAR(500);
ALTER TABLE ci_runs ADD COLUMN release_owner_subject VARCHAR(500);
CREATE INDEX ix_ci_runs_release_queue ON ci_runs(release_status, release_next_attempt);
