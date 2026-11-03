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
- [x] Created public Cloud DNS zone `aio-sarvaikam-app` for `app.sarvaikam.com`; expanded the dedicated `aioDnsZoneEditor` role and bound its GSA to the cert-manager KSA.
- [x] Installed ExternalDNS `v0.24.0` in namespace `external-dns`, restricted to `app.sarvaikam.com` with `upsert-only` policy. Its pod is Ready and authenticates through Workload Identity; logs report no Cloud DNS errors. Added the direct federated-principal IAM binding required for token exchange.
- [x] Applied the platform cluster metadata via Helm revision 14: dev/staging/prod target the in-cluster cluster, custom-domain ingress IP is `34.180.3.18`, and DNS zone is `app.sarvaikam.com`. Default-host TLS remains disabled because the existing `nip.io` hosts are outside the delegated DNS zone; portal remains at `aio.34.180.3.18.nip.io`, image `0.10.3`.
- [x] Created the GTS production ACME account and `gts` ClusterIssuer using EAB and Cloud DNS DNS-01 restricted to `app.sarvaikam.com`. EAB material is stored only in the `cert-manager` namespace; the ClusterIssuer reports Ready.

## Current state / remaining

- [x] **GTS issuer.** Production `gts` ClusterIssuer is Ready with a Cloud DNS DNS-01 solver for `app.sarvaikam.com`.
- [ ] **Restore apex DNS first.** After the nameserver change, public resolvers return `SERVFAIL` for `sarvaikam.com` and `www.sarvaikam.com`; the Google nameservers refuse apex queries because the Cloud DNS zone contains only `app.sarvaikam.com`. In Hostinger, restore the domain's default nameservers (`ns1.dns-parking.com`, `ns2.dns-parking.com`) to restore the root zone. Then, in Hostinger DNS Zone Editor, add NS records for host `app` pointing to `ns-cloud-b1.googledomains.com.`, `ns-cloud-b2.googledomains.com.`, `ns-cloud-b3.googledomains.com.`, and `ns-cloud-b4.googledomains.com.` Do not change apex or `www` records.
- [x] **Custom-domain UI rollout.** GKE runs `aio-platform:0.10.3`, configured in `platform/deploy/values-platform.yaml`. Cloud Build succeeded, the deployment rollout completed, the platform pod is Ready, and the served frontend bundle includes the Domains UI.
- [ ] **Real-domain validation.** After issuer setup, add a non-production hostname, point DNS at the target cluster ingress IP, and verify DNS, Ingress, Certificate readiness, HTTPS, and path routing before using production.
- [ ] **sarvaikam.com mapping.** Requested route: `app.sarvaikam.com` → project `sarvaikam`, `dev`, using Cloud DNS. The zone currently contains only its NS/SOA records; no app route or A/TXT records exist. Restore Hostinger apex DNS and set up child NS delegation, then add the hostname in the Domains tab. Preserve the existing apex and `www` records.
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

1. Restore the Hostinger apex nameservers and confirm `sarvaikam.com` and `www.sarvaikam.com` resolve again.
2. Delegate only `app.sarvaikam.com` to the four Cloud DNS nameservers listed above using Hostinger's DNS Zone Editor; verify public NS resolution.
3. Add `app.sarvaikam.com` → `sarvaikam` / `dev` in the Domains tab.
4. Verify ExternalDNS A/TXT records, Certificate readiness, HTTPS, and application routing; document renewal.
5. Monitor Cloud SQL and retain the in-cluster PostgreSQL PVC until the chosen rollback-retention period has elapsed.
