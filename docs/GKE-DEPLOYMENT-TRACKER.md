# GKE deployment and migration tracker

Last checked: 2026-11-03
Cluster: `project-d28840cd-be2f-4901-85e` / `asia-south1` / `cluster-1`

Use this file as the running record for the platform deployment, cluster prerequisites, and database migrations.
Update it after each production deployment or schema migration.

## Completed

- [x] GKE context is configured and reachable; `aio-platform` namespace is active.
- [x] Platform deployment and in-cluster PostgreSQL are healthy.
- [x] cert-manager `v1.21.3` installed in namespace `cert-manager`; controller, cainjector, and webhook are Ready.
- [x] cert-manager installation is pinned and repeatable via `bootstrap/install-cert-manager.sh`; the generic, GCP, and workload-cluster bootstrap scripts call it.
- [x] Flyway reports migrations V1 through V5 as successful on the GKE database. V5 creates the `domains` table.
- [x] Custom domain backend and Helm route support are committed in `d1e9d7b`.
- [x] Domain API/UI authorization and core domain/watcher tests are included in that commit.
- [x] Previously built and deployed `aio-platform:0.10.2`; superseded by `0.10.3`.
- [x] Built `aio-platform:0.10.3` from pushed commit `3f04b5d`. Cloud Build `086ea478-9cc1-4fda-972d-1dde4ecbc471` succeeded; image digest `sha256:03cda44f98bd712d992009f1e5621a7ea9b412d0bfb2d45d4ba0a72c9f3229f5`.
- [x] Deployed `0.10.3` to GKE; rollout completed and the platform pod is Ready. `http://aio.34.180.3.18.nip.io/` returns HTTP 200, and the served frontend bundle includes the Domains UI.
- [x] Migrated the platform database to Cloud SQL instance `aio-002` (`asia-south1`, PostgreSQL 18) on 2026-11-02. The app connects through the Cloud SQL Java Socket Factory and Workload Identity.
- [x] Copied and verified all 6 projects on Cloud SQL. Flyway validated all 5 migrations and reports schema version V5; `/actuator/health/readiness` returned `{"status":"UP"}`.
- [x] Stopped the in-cluster PostgreSQL StatefulSet after verification and retained its bound PVC for rollback. Removed the temporary migration pod, Secret, and elevated Cloud SQL migration user.
- [x] Created on-demand Cloud SQL backup `1793613161796` after cutover, restored it to a disposable PostgreSQL 18 instance, verified all 6 projects and Flyway V5, then deleted the test instance. Production remained healthy and the source PVC remains bound.
- [x] Created public Cloud DNS zone `aio-sarvaikam-app` for `app.sarvaikam.com`; the parent zone remains on Hostinger and does not delegate the `app` child zone. Hostinger's DNS editor does not offer NS records.
- [x] Installed ExternalDNS `v0.24.0` with `upsert-only` and a filter for `app.sarvaikam.com`. It created an A record in the Cloud DNS child zone, but that record is not publicly visible without parent-zone NS delegation.
- [x] Created the GTS production ACME account; EAB credentials are stored in the `cert-manager` namespace. The `gts` ClusterIssuer is Ready and uses the HTTP-01 solver with ingress-nginx.
- [x] Saved `app.sarvaikam.com` → `sarvaikam` / `dev` in the AIO Domains tab. Argo CD deployed the custom Ingress.
- [x] Configured the platform for Hostinger-managed DNS: `APP_CLUSTERS_0_DNSZONE` is blank and the route's ExternalDNS annotation is off. Default-host TLS remains off; the portal is `aio.34.180.3.18.nip.io`, image `0.10.3`.

## Current state / remaining

- [x] **GTS issuer.** Production `gts` ClusterIssuer is Ready with an HTTP-01 solver using ingress-nginx.
- [x] **Apex DNS preserved.** The domain uses Hostinger nameservers and apex/`www` records resolve. Do not change domain-level nameservers.
- [ ] **Hostinger A record.** Add a Hostinger DNS `A` record: host `app`, target `34.180.3.18`, default TTL. This keeps the existing apex and `www` DNS unchanged.
- [ ] **Certificate retry.** After the A record resolves publicly, replace the pending DNS-01 order so cert-manager retries with HTTP-01; verify the certificate becomes Ready.
- [x] **Custom-domain UI rollout.** GKE runs `aio-platform:0.10.3`, configured in `platform/deploy/values-platform.yaml`. Cloud Build succeeded, the deployment rollout completed, the platform pod is Ready, and the served frontend bundle includes the Domains UI.
- [ ] **Real-domain validation.** After issuer setup, add a non-production hostname, point DNS at the target cluster ingress IP, and verify DNS, Ingress, Certificate readiness, HTTPS, and path routing before using production.
- [ ] **sarvaikam.com mapping.** Route `app.sarvaikam.com` → `sarvaikam` / `dev` is saved and its Ingress is deployed. Complete the Hostinger A-record + GTS HTTP-01 setup and verify HTTPS. Preserve existing apex and `www` records.
- [x] **Database restore rehearsal.** Cloud SQL automated backups and point-in-time recovery are enabled; backup `1793613161796` restored successfully and passed data/schema verification. The in-cluster PostgreSQL PVC remains available as an additional rollback copy.

## Deployment procedure

1. Confirm the working tree/commit and run backend tests, frontend build, and Helm lint/render.
2. Choose a new immutable platform image tag; update `platform/deploy/values-platform.yaml`.
3. Build from the `platform` context:

   ```sh
   gcloud builds submit platform \
     --tag asia-south1-docker.pkg.dev/project-d28840cd-be2f-4901-85e/aio/aio-platform:<tag>
   ```

4. Update the image in namespace `aio-platform` and wait for rollout:

   ```sh
   kubectl -n aio-platform set image deployment/aio-platform \
     app=asia-south1-docker.pkg.dev/project-d28840cd-be2f-4901-85e/aio/aio-platform:<tag>
   kubectl -n aio-platform rollout status deployment/aio-platform --timeout=600s
   ```

5. Verify the served UI, readiness endpoint, pod logs, and live image. The current platform release is managed by Helm:

   ```sh
   helm upgrade aio-platform helm/app --namespace aio-platform \
     --values platform/deploy/values-platform.yaml --wait --timeout 10m
   ```

   If the release is later moved to Argo CD, commit the same values and sync the Argo CD application instead.
6. Update this tracker with tag, commit, date, migration version, and verification results.

## Database migration procedure

- Flyway migrations are in `platform/backend/src/main/resources/db/migration/` and run automatically at application
  startup. Current recorded schema version is **V5 (`domains`)**.
- Never edit a migration already applied to a shared database. Add the next sequential migration (currently V6).
- Prefer additive, backward-compatible changes. For a breaking schema change, use expand → deploy compatible code →
  backfill → deploy code using the new schema → contract in a later migration.
- Before applying a destructive migration or moving databases, take a verified backup and document the restore point.
- After rollout, verify `flyway_schema_history`, application readiness, and affected API/UI behavior. Do not mark a
  migration complete based only on a successful Kubernetes rollout.
- Roll back application code only when it remains compatible with the applied schema. Correct schema mistakes with a
  forward migration; restore a backup only under an explicit recovery plan.

## Follow-up order

1. Keep Hostinger nameservers for the parent zone and add `app A 34.180.3.18`.
2. After public DNS resolves to `34.180.3.18`, replace the pending order so cert-manager uses the configured GTS HTTP-01 solver.
3. Verify the Certificate becomes Ready, then test HTTPS and application routing; document renewal.
4. Monitor Cloud SQL and retain the in-cluster PostgreSQL PVC until the chosen rollback-retention period has elapsed.
