# GKE deployment and migration tracker

Last checked: 2026-11-02
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

## Current state / remaining

- [ ] **Issuer not configured.** No `ClusterIssuer` exists. The requested issuer strategy is Google Trust Services (GTS), but its EAB credentials and DNS-01 setup have not been supplied. Do not expect certificates to issue until a `gts` `ClusterIssuer` is created and Ready.
- [ ] **Cloud DNS zone not configured.** Cloud DNS API is enabled, but the project has no managed zones. Provide the public zone and configure external-dns plus cert-manager DNS-01 permissions if GTS is to manage DNS challenges. Without automated DNS, add A records manually.
- [x] **Custom-domain UI rollout.** GKE runs `aio-platform:0.10.3`, configured in `platform/deploy/values-platform.yaml`. Cloud Build succeeded, the deployment rollout completed, the platform pod is Ready, and the served frontend bundle includes the Domains UI.
- [ ] **Real-domain validation.** After issuer setup, add a non-production hostname, point DNS at the target cluster ingress IP, and verify DNS, Ingress, Certificate readiness, HTTPS, and path routing before using production.
- [ ] **Database backup procedure.** No backup was taken as part of this setup. Record a successful PostgreSQL backup and restore test before a future destructive migration or database move.

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

5. Verify the served UI, readiness endpoint, pod logs, and live image. The preferred long-term deployment path is the
   repository's Argo CD application; local Helm is not installed on the current workstation.
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

1. Obtain GTS EAB key ID/secret and decide whether GTS DNS-01 will use Cloud DNS or another DNS provider.
2. Configure DNS-zone ownership and least-privilege service-account access; create and verify the `gts` ClusterIssuer.
3. Test one non-production real domain end to end; then document the production DNS and certificate renewal process.
4. Establish scheduled PostgreSQL backups and perform a restore test. Consider Cloud SQL separately from this feature rollout.
