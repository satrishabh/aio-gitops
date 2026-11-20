-- Catalogs and aggregate counts only. No user records, credentials, logs or findings are printed.
\pset pager off
\timing on
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '1s';
SELECT current_database() AS database, current_setting('server_version') AS postgres_version,
       pg_size_pretty(pg_database_size(current_database())) AS database_size;
SELECT rolsuper, rolcreatedb, rolcreaterole,
       has_schema_privilege(current_user, 'public', 'CREATE') AS can_create_in_public
FROM pg_roles WHERE rolname = current_user;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT relname AS table_name, n_live_tup AS estimated_rows, n_dead_tup AS estimated_dead_rows,
       pg_size_pretty(pg_total_relation_size(relid)) AS total_size, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;
SELECT c.conrelid::regclass AS table_name, c.conname AS foreign_key_without_full_leading_index
FROM pg_constraint c WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
AND NOT EXISTS (
  SELECT 1 FROM pg_index i WHERE i.indrelid = c.conrelid AND i.indisvalid
  AND i.indpred IS NULL
  AND (i.indkey::smallint[])[0:cardinality(c.conkey)-1] @> c.conkey
);
SELECT 'projects_without_owner_subject' AS check_name, count(*) AS rows FROM projects WHERE owner_subject IS NULL
UNION ALL SELECT 'standalone_scans_without_owner_subject', count(*) FROM scan_runs WHERE project_id IS NULL AND owner_subject IS NULL
UNION ALL SELECT 'audit_without_actor_subject_including_system_events', count(*) FROM audit_log WHERE actor_subject IS NULL
UNION ALL SELECT 'ci_rerun_reference_missing', count(*) FROM ci_runs r LEFT JOIN ci_runs parent ON parent.id=r.rerun_of WHERE r.rerun_of IS NOT NULL AND parent.id IS NULL
UNION ALL SELECT 'scan_rerun_reference_missing', count(*) FROM scan_runs r LEFT JOIN scan_runs parent ON parent.id=r.rerun_of WHERE r.rerun_of IS NOT NULL AND parent.id IS NULL;
SELECT 'run_steps' AS table_name, count(*) FILTER (WHERE log_text IS NOT NULL) AS archived_logs,
       coalesce(sum(octet_length(log_text)), 0) AS log_bytes FROM run_steps
UNION ALL SELECT 'scan_steps', count(*) FILTER (WHERE log_text IS NOT NULL), coalesce(sum(octet_length(log_text)), 0) FROM scan_steps;
SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname='public' ORDER BY tablename;
COMMIT;
