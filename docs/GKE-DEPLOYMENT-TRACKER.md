# GKE deployment and migration tracker

Last checked: 2026-11-07
Cluster: `project-d28840cd-be2f-4901-85e` / `asia-south1` / `cluster-1`

Use this file as the running record for the platform deployment, cluster prerequisites, and database migrations.
Update it after each production deployment or schema migration.

## Completed

- [x] Released AIO `0.12.5` on 2026-11-07 with the AIO wordmark and smaller “by Sarvaikam” attribution beside the mobile menu. Source `bf8ca76`; release `2026a6c`; Cloud Build `499b5da0-4269-45b7-a75d-4cf7b51a8a1e`; digest `sha256:5a64663597336d7e8350a9d7ba0c2f591ac6c8a290de2ca489c1419e3ad9640f`. Frontend build, existing responsive navigation checks, and visual header inspection passed locally. Live login and JS bundle returned HTTP 200, and the served bundle contains the new branding. Rollout completed; readiness UP, Argo CD Synced/Healthy, and final pod Ready with zero restarts.

- [x] Released AIO `0.12.4` on 2026-11-07: restored the desktop/tablet login grid, preserved mobile sign-in order, removed redundant mobile breadcrumb labels, and replaced the appearance menu with a subtle one-tap light/dark icon. UI source `7b90c84` (desktop correction `c8815be`); release `b81a20f`, merged with unrelated automated app deployment in `52e69a1`. Cloud Build `ee1f482f-ca51-4da9-bed0-a26d37e5820d` succeeded; digest `sha256:daa7805e3351d6b37b3b2627031bec987156d025e75a525bb97bce76625269a3`. The earlier superseded build `ed3a17bb-3e32-4697-9972-fec00f1a29a3` was cancelled before publication.
- [x] During the `0.12.4` rollout, the initial pod exceeded the liveness startup allowance and restarted. Increased only the platform liveness initial delay from 60 to 120 seconds in `fafb234`; readiness gating remains active. Replacement pod `aio-platform-75d598d847-qf7v8` became Ready with zero restarts; rollout completed, readiness is UP, and Argo CD reports Synced/Healthy. Schema remains V6.
- [x] Verified `0.12.4` using browser checks across 10 widths from 320 to 1920 px in both appearances, including desktop column geometry, mobile order, one-click and keyboard theme switching, persistence, cross-tab synchronization, default device appearance, no theme popup, and no JavaScript errors. Locally checked touch switching, hidden mobile breadcrumbs, drawer behavior, and preserved desktop sidebar using API fixtures. Authenticated production workspace screens were not exercised by this browser run.

- [x] Released AIO `0.12.3` on 2026-11-07 through Argo CD automatic sync. UI source `d04903f`; release `4a218db`; Cloud Build `9330e53a-dcd1-442b-a912-13a6c9101629`; image digest `sha256:b18fd23ba1d704827ed05d7cbddc4ac27315921a23d335ebf1893f50fecbd4cb`. Rollout completed, readiness is UP, and Argo CD reports Synced/Healthy.
- [x] Verified the live `0.12.3` login: hover reveal, Escape/outside dismissal, manual/system themes, reload persistence, cross-tab sync, keyboard selection, mobile sign-in visibility, and both appearances at 320/390/768/1440 px, with no browser JavaScript errors. Locally verified mobile touch and workspace drawer behavior using API fixtures; the desktop sidebar remains visible. Authenticated production workspace screens were not exercised by this browser run.

- [x] Released AIO `0.12.2` on 2026-11-07 through Argo CD automatic sync. UI source commit `4048e0b`; release commit `562763a`. Cloud Build `3ace62cf-f451-46ed-b317-9be533cef5b2` succeeded; image digest `sha256:da28ce895f2b6df9e29c614bf673a9c362319043ed78f23e42721ef2958c7eec`.
- [x] Verified `0.12.2` rollout and readiness (`UP`); startup validated all six Flyway migrations, with schema V6 unchanged. Preflight checks passed: 64 backend tests, four appearance tests, frontend production build, and Helm lint/render.
- [x] Verified the deployed HTTPS login in headless Edge: Light/Dark changes the hero, preference survives reload, cross-tab synchronization works, Auto follows live device appearance changes, Home/End selection works, and both appearances have no page overflow at 320/390/768/1440 px. No browser JavaScript errors detected. Authenticated workspace screens were not exercised by this browser run.

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
- [x] Built `aio-platform:0.10.4` from commit `4842de5`. Cloud Build `18e066ec-c7fa-43c8-b20a-8d721492ff61` succeeded with image digest `sha256:6c4854c7c57a46d6544c8a015d48204110adba90cdfe2c8b5372c093aad83488`; Helm revision 17 is deployed and Ready.
- [x] Migrated the platform database to Cloud SQL instance `aio-002` (`asia-south1`, PostgreSQL 18) on 2026-11-02. The app connects through the Cloud SQL Java Socket Factory and Workload Identity.
- [x] Copied and verified all 6 projects on Cloud SQL. Flyway validated all 5 migrations and reports schema version V5; `/actuator/health/readiness` returned `{"status":"UP"}`.
- [x] Stopped the in-cluster PostgreSQL StatefulSet after verification and retained its bound PVC for rollback. Removed the temporary migration pod, Secret, and elevated Cloud SQL migration user.
- [x] Created on-demand Cloud SQL backup `1793613161796` after cutover, restored it to a disposable PostgreSQL 18 instance, verified all 6 projects and Flyway V5, then deleted the test instance. Production remained healthy and the source PVC remains bound.
- [x] Created public Cloud DNS zone `aio-sarvaikam-app` for `app.sarvaikam.com`; the parent zone remains on Hostinger and does not delegate the `app` child zone. Hostinger's DNS editor does not offer NS records.
- [x] Installed ExternalDNS `v0.24.0` with `upsert-only` and a filter for `app.sarvaikam.com`. It created an A record in the Cloud DNS child zone, but that record is not publicly visible without parent-zone NS delegation.
- [x] Created the GTS production ACME account; EAB credentials are stored in the `cert-manager` namespace. The `gts` ClusterIssuer is Ready and uses the HTTP-01 solver with ingress-nginx.
- [x] Routed `learn.sarvaikam.com` to `sarvaikam` / `dev`; its GTS certificate is Ready and both the homepage and `/topics/terraform` return HTTPS 200.
- [x] Removed `app.sarvaikam.com` from the Sarvaikam project after Learn TLS was verified. Argo CD synced commit `0fd53e8`.
- [x] Moved the AIO portal to `https://app.sarvaikam.com` with GTS TLS and HTTP-to-HTTPS redirect. GitHub OAuth callback is saved and sign-in was verified. `APP_PUBLIC_URL` now uses the new hostname.
- [x] Kept `aio.34.180.3.18.nip.io` as a legacy HTTP alias so existing GitHub webhooks continue to reach the platform; remove it only after those webhook URLs are migrated.
- [x] Configured Hostinger-managed DNS: `APP_CLUSTERS_0_DNSZONE` is blank and the route's ExternalDNS annotation is off. Apex and `www` records remain unchanged.
- [x] Created `satrishabh/user-profile-manager` as a separate repository and AIO project. AIO build `user-profile-manager-ci-7cl76` passed clone, shell validation, Kaniko build, Trivy gating, and the dev GitOps update for image `b252e7a94418`.
- [x] Deployed Keycloak `26.8.1` with the patched Guava `33.7.2-jre`, Cloud SQL, Google/GitHub identity providers, Resend SMTP, verification email, password reset, and PKCE for `sarvaikam-learn`.
- [x] Routed `profile.sarvaikam.com` through AIO GitOps with GTS TLS. The certificate is Ready; OIDC discovery and the account console return HTTPS 200, both social-login redirects resolve to their providers, and the pod is Ready with zero restarts.

## Current state / remaining

- [x] **GTS issuer.** Production `gts` ClusterIssuer is Ready with an HTTP-01 solver using ingress-nginx.
- [x] **Apex DNS preserved.** The domain uses Hostinger nameservers and apex/`www` records resolve. Do not change domain-level nameservers.
- [x] **Hostinger A records.** `app.sarvaikam.com` and `learn.sarvaikam.com` resolve to `34.180.3.18`; apex and `www` continue to resolve to their existing Hostinger targets.
- [x] **GTS certificates.** `tls-learn-sarvaikam-com` and `aio-platform-tls` are Ready; both sites return HTTPS 200.
- [x] **Current platform rollout.** GKE runs `aio-platform:0.12.5`; Argo CD reports Synced/Healthy, rollout completed, readiness is UP, and the served frontend includes the mobile AIO/by Sarvaikam branding. Appearance/layout checks from 320 to 1920 px passed for `0.12.4`; responsive navigation checks were repeated locally for the branding change. The earlier `0.10.4` rollout verified mobile Overview overflow.
- [x] **Real-domain validation.** Verified Learn homepage/topic routing, AIO HTTPS and HTTP redirect, GitHub sign-in, and the legacy webhook alias.
- [x] **Sarvaikam host mapping.** `learn.sarvaikam.com` serves the learning app and `app.sarvaikam.com` serves AIO. Apex and `www` records remain unchanged.
- [x] **Shared User Profile service.** `satrishabh/user-profile-manager` is deployed through AIO at `https://profile.sarvaikam.com` with central Google/GitHub OIDC, realm roles, verification email, password reset, and the reusable `sarvaikam-learn` PKCE client.
- [ ] **Organization homepage.** The people-first/product-led redesign is local in `sarvaikam/website`; do not create its AIO project or publish apex/`www` until the preview is approved and `app.sarvaikam.com` is confirmed as its AIO destination.
- [x] **Database restore rehearsal.** Cloud SQL automated backups and point-in-time recovery are enabled; backup `1793613161796` restored successfully and passed data/schema verification. The in-cluster PostgreSQL PVC remains available as an additional rollback copy.

## Deployment procedure

1. Confirm the working tree/commit and run backend tests, frontend build, and Helm lint/render.
2. Choose a new immutable platform image tag; update `platform/deploy/values-platform.yaml`.
3. Build from the `platform` context:

   ```sh
   gcloud builds submit platform --region=asia-south1 \
     --tag asia-south1-docker.pkg.dev/project-d28840cd-be2f-4901-85e/aio/aio-platform:<tag>
   ```

4. Production is now managed by Argo CD with automatic sync. Commit and push the tested source and updated image tag in `platform/deploy/values-platform.yaml`; refresh the `aio-platform` Application, then wait for it to apply the new image and complete rollout. The direct image update below is for emergency intervention only; Argo CD will restore the GitOps value:

   ```sh
   kubectl -n aio-platform set image deployment/aio-platform \
     app=asia-south1-docker.pkg.dev/project-d28840cd-be2f-4901-85e/aio/aio-platform:<tag>
   kubectl -n aio-platform rollout status deployment/aio-platform --timeout=600s
   ```

5. Verify the served UI, readiness endpoint, pod logs, live image digest, and Argo CD Synced/Healthy status. The older Helm release remains in cluster history; the following command is for the legacy Helm workflow, not the current production deployment:

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
