#!/bin/sh
set -eu
# Run only inside the isolated postgres-rehearsal pod. Contains synthetic data only.
psql -U rehearsal_admin -d rehearsal -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE IF NOT EXISTS migration_probe (id integer PRIMARY KEY, marker text NOT NULL);
INSERT INTO migration_probe VALUES (1, 'AIO_AZURE_RESTORE_VERIFIED') ON CONFLICT (id) DO NOTHING;
SQL
export PGPASSWORD="$(cat /etc/postgres-password/password)"
connection='host=postgres-rehearsal.aio-validation.svc.cluster.local user=rehearsal_admin dbname=rehearsal sslmode=verify-full sslrootcert=/etc/postgres-tls/tls.crt'
psql "$connection" -v ON_ERROR_STOP=1 -Atc 'SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()'
if psql 'host=postgres-rehearsal.aio-validation.svc.cluster.local user=rehearsal_admin dbname=rehearsal sslmode=disable' -Atc 'SELECT 1' >/dev/null 2>&1; then
  echo 'ERROR: Unencrypted TCP connection was accepted' >&2
  exit 1
fi
echo 'UNENCRYPTED_TCP_REJECTED'
unset PGPASSWORD
if psql -U rehearsal_admin -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname='rehearsal_restore_probe'" | grep -q 1; then
  echo 'Restore probe already exists; refusing to overwrite it' >&2
  exit 1
fi
pg_dump -U rehearsal_admin -d rehearsal -Fc -f /tmp/aio-synthetic-probe.dump
createdb -U rehearsal_admin rehearsal_restore_probe
pg_restore -U rehearsal_admin -d rehearsal_restore_probe --exit-on-error --no-owner --no-acl /tmp/aio-synthetic-probe.dump
psql -U rehearsal_admin -d rehearsal_restore_probe -v ON_ERROR_STOP=1 -Atc 'SELECT marker FROM migration_probe WHERE id=1'
rm /tmp/aio-synthetic-probe.dump
