#!/usr/bin/env bash
# One-time copy of the platform database from the in-cluster Postgres (statefulset aio-postgres) to Cloud SQL.
# Run from Cloud Shell (has gcloud, kubectl and psql). Called by gcp-setup.sh; safe to run on its own:
#   SQL_INSTANCE_CONN=<project>:<region>:<instance> bash bootstrap/migrate-to-cloudsql.sh
# The platform is scaled to 0 during the copy so no writes are lost. The old Postgres is left untouched
# (delete it yourself once you are happy: kubectl -n aio-platform delete -f platform/deploy/postgres.yaml).
set -euo pipefail

NS=aio-platform
DB=aio
DB_USER=aio
: "${SQL_INSTANCE_CONN:?set SQL_INSTANCE_CONN=<project>:<region>:<instance>}"
PROXY_VERSION=v2.26.3
PROXY_SHA256=5533a05cdbf12f8a0cb46db34c775847a496e181d47f863284d3d02b0fad49fa   # linux.amd64
PORT=6543

if ! kubectl -n "$NS" get statefulset aio-postgres >/dev/null 2>&1; then
  echo "No in-cluster Postgres (statefulset aio-postgres) found - nothing to migrate."; exit 0
fi

DB_PASSWORD=$(kubectl -n "$NS" get secret aio-platform-secrets -o jsonpath='{.data.DB_PASSWORD}' | base64 -d)
DUMP=/dev/shm/aio-dump.sql   # tmpfs: never written to the persistent disk
trap 'rm -f "$DUMP"; [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null || true' EXIT

# Cloud SQL Auth Proxy on localhost, authenticated with your gcloud login (needs roles/cloudsql.client)
PROXY=./cloud-sql-proxy
if [ ! -x "$PROXY" ]; then
  curl -fsSLo "$PROXY" "https://storage.googleapis.com/cloud-sql-connectors/cloud-sql-proxy/${PROXY_VERSION}/cloud-sql-proxy.linux.amd64"
  echo "${PROXY_SHA256}  ${PROXY}" | sha256sum -c -
  chmod +x "$PROXY"
fi
"$PROXY" --gcloud-auth --port "$PORT" "$SQL_INSTANCE_CONN" >/tmp/cloud-sql-proxy.log 2>&1 &
PROXY_PID=$!
for _ in $(seq 1 30); do (echo > /dev/tcp/127.0.0.1/$PORT) 2>/dev/null && break; sleep 1; done

export PGPASSWORD="$DB_PASSWORD"
psql_cloud() { psql -h 127.0.0.1 -p "$PORT" -U "$DB_USER" -d "$DB" -v ON_ERROR_STOP=1 "$@"; }

TABLES=$(psql_cloud -tAc "select count(*) from information_schema.tables where table_schema='public'")
if [ "$TABLES" != "0" ] && [ "${FORCE:-}" != "1" ]; then
  echo "Cloud SQL database '$DB' already has $TABLES tables - assuming it was migrated. Re-run with FORCE=1 to overwrite."
  exit 0
fi

echo "==> stopping the platform so nothing writes during the copy"
kubectl -n "$NS" scale deploy/aio-platform --replicas=0 2>/dev/null || true
kubectl -n "$NS" wait --for=delete pod -l app.kubernetes.io/name=aio-platform --timeout=120s 2>/dev/null || true

echo "==> dumping in-cluster Postgres"
kubectl -n "$NS" exec aio-postgres-0 -- env PGPASSWORD="$DB_PASSWORD" \
  pg_dump -U "$DB_USER" -d "$DB" --no-owner --no-acl --clean --if-exists > "$DUMP"
echo "    $(wc -l < "$DUMP") lines"

echo "==> restoring into Cloud SQL ($SQL_INSTANCE_CONN)"
psql_cloud -q -f "$DUMP"
psql_cloud -tAc "select 'projects: ' || count(*) from projects"

echo "==> done. gcp-setup.sh points the platform at Cloud SQL and starts it again."
