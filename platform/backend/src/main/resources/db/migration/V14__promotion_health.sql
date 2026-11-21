-- Safe promotion: a promotion is DEPLOYING after its commit until the target env is healthy (DONE) or not (FAILED).
-- Additive only; existing rows keep their status.
ALTER TABLE promotions ADD COLUMN deploying_since TIMESTAMPTZ;
ALTER TABLE promotions ADD COLUMN detail TEXT;
ALTER TABLE promotions ADD COLUMN copy_settings BOOLEAN;
-- JSON {name: value} of edited plain env values that Copy settings adds (never Secret data)
ALTER TABLE promotions ADD COLUMN env_overrides TEXT;
CREATE INDEX IF NOT EXISTS promotions_status_idx ON promotions (status);
