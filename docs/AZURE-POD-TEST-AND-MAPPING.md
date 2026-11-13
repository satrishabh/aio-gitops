# Pod acceptance and application mapping — 2026-11-14

## Observed pod and service results

Azure live service pods are Ready; certificate/Tekton/image-check pods completed successfully. Learn's newer image now passes backend-aware `/ready`, not just the static Nginx `/health`. PostgreSQL retained the restored schemas after its certificate restart. All active GCP application pods are healthy after the source Learn rollout recovery.

Two historical GCP `ci` scan pods remain Failed: `sarvaikam-home-ci-fsc2z-scan-pod` and `sarvaikam-home-ci-ll22c-scan-pod`. Their gate step exited 1; these are failed security checks, not serving application pods. The latter summary reported 3 high and 2 medium findings. They were not deleted, bypassed or reported as successful. Full build/scan/delivery acceptance remains pending.

The newer source Learn image `0bee3f3507d0` could not start Nginx because `proxy_read_timeout` was specified twice inside its asset route. GCP was restored to the exact known-serving `86e08ad3dcac` image, keeping its data and routes. GitOps is Synced/Healthy at `1c5de1e83581d74f276e9a01d0439d674bef6589`. The values also allow 180 seconds before liveness checks start; readiness still gates traffic. An increased delay alone did not fix the duplicate directive.

Azure tests the newer immutable image `sha256:9b6605c124fd837aa55006ab6ef32571f97288876a0f1273968bc70ee1cb94fb` with the explicit `azure-nginx-proxy-compat.yaml` ConfigMap. It removes the shared timeout directive; normal locations retain Nginx's default 60 seconds and the asset location keeps its explicit 300 seconds. The image requires this override until a corrected artifact is built. Do not deploy it elsewhere without the required configuration or a repaired image.

Java initially rejected the self-signed database certificate's empty issuer DN. The Certificate now includes commonName and organization. It was reissued, the public trust ConfigMap refreshed and PostgreSQL restarted to load the corrected certificate. Verification stays enabled. For future rotations use `refresh-rehearsal-trust.py`, reload/restart PostgreSQL after the Secret is projected, and restart clients so Java/Node load the new trust.

## Read-only Azure application acceptance

`ops/portable/check-learn-http.py` passed:

- HTTP 200 for `/health`, `/ready`, auth/progress health, homepage and all five referenced built UI assets.
- HTTP 200 for the content API with 10 restored categories, and the reels API using its separate schema.
- HTTP 401 for unauthenticated auth and progress user endpoints.
- Four encrypted application database sessions, zero unencrypted sessions and zero privileged application roles at observation.
- Learn role can use only `sarvaikam`; reels role can use only `sarvaikam_reels`. Cross-schema usage checks were false.

The application uses fresh Azure-only passwords and a separate JWT signing key. Production identity client secrets were not copied; `PROFILE_ISSUER` points to an unavailable loopback rehearsal endpoint. Real login/registration, owner/non-owner acceptance and visual browser tests have not run. Object-storage access remains unverified. Structured results are in `evidence/azure-learn-acceptance.json`.

## Applied isolated mapping

| Service | Azure deployment | Database | Schema / role | Status |
| --- | --- | --- | --- | --- |
| Learn content/auth/progress | aio-validation/sarvaikam-learn | aio_rehearsal | sarvaikam / learn_rehearsal | Ready; API/schema/TLS checks passed |
| Learn reels | Same deployment | Same database | sarvaikam_reels / reels_rehearsal | API/schema/TLS checks passed |
| Homepage | aio-validation/sarvaikam-home | None | None | Ready; prior HTTP checks passed |
| Profile manager | Not deployed on Azure | Pending | Pending | Image pull only verified |
| Blog | Not deployed on Azure | Pending | Pending | Image pull only verified |
| AIO hub | Not deployed on Azure | Pending | Pending | Earlier exact-image Java runtime test only |

Java uses explicit JDBC `currentSchema`; auth/Go use schema-scoped role defaults, and Go also receives a schema-aware DSN. Flyway and Hibernate main schema settings are explicit. `configure-learn-schema-runtime.py` creates new target roles and Secrets and refuses implicit credential rotation. Its role/ownership changes are transactional; interruption after role creation but before Secret creation requires operator recovery rather than an automatic retry with different passwords.

## Public route mapping is not switched

`learn.sarvaikam.com`, `sarvaikam.com`, `profile.sarvaikam.com`, blog routes and `app.sarvaikam.com` continue serving GCP. Azure services are private ClusterIP endpoints accessed through local port-forwards. No ingress, ExternalDNS or competing Argo environment mapping was installed for cutover.

Before public mapping: validate target identity and callbacks, object storage, remaining applications, security gates and trusted external TLS; provision a target ingress/validation hostname; take a fresh synchronized data checkpoint; define write authority and rollback. Changing AIO's existing single destination per environment would cascade replacement, so it must not be used to preview Azure. The tracker displays isolated mapping evidence separately from live traffic ownership.
