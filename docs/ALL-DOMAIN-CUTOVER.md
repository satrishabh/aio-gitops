# All-domain Azure cutover contract

User decision: move **all domains only after their Azure applications are ready**. No partial public cutover has been made. All public DNS remains on GCP-serving addresses.

## Prepared routing foundation

Private Azure ingress: Helm release `aio-azure-ingress`, namespace `aio-ingress`, chart Traefik 42.4.0 / application v3.7.18. Archive SHA-256 and source are recorded in `ops/portable/ingress.lock.json`. `azure-ingress-values.yaml` pins a ClusterIP service; it does not allocate a public load balancer, public IP or additional node. IngressClass `aio-azure` is not the cluster default, watches validation routes and exposes no dashboard.

`azure-private-routes.yaml` maps canonical Learn/homepage hosts internally. TLS routing tests passed for Learn `/ready`, Learn reels and homepage. An unmapped hostname returned 404. The self-signed test certificate was trusted explicitly for these tests only; this is not evidence of trusted public certificates. Test results: `evidence/azure-private-ingress.json`.

Authoritative nameservers for `sarvaikam.com` are `ns1.dns-parking.com` and `ns2.dns-parking.com` (Hostinger). A GCP zone named `aio-sarvaikam-app` exists for `app.sarvaikam.com`, but delegation was not demonstrated: an NS query for `app.sarvaikam.com` returned the root Hostinger authority. Updating that GCP zone must not be assumed to change public routing. No Hostinger DNS credential/connection was found in the session. DNS access and trusted certificate validation remain required before cutover.

## Remaining data migration, concrete proposed scope

Metadata-only audit completed using source-local PG18 clients referencing existing source Secret keys. All clients were removed. No records, passwords, OAuth secrets or dumps from the following databases were transferred.

| GCP Cloud SQL aio-002 source database | Source tables | Observed size | Azure database | Proposed target schema |
| --- | --- | --- | --- | --- |
| profile_manager | 101 | 15,226,559 bytes | aio_rehearsal | profile_manager |
| sarvaika_blog | 9 | 8,369,855 bytes | aio_rehearsal | sarvaika_blog |
| aio | 13 | 11,179,711 bytes | aio_rehearsal | aio_platform |

Each source has only `public`, only `plpgsql`, no custom functions and no large objects. They fit the current isolated target's small-database rehearsal allowance. Proposed transfer is the same consistent binary snapshot/restore and native schema-renaming method used for Learn/reels, into new schemas without overwriting existing Learn/reels objects. Source credentials stay on GCP and source remains authoritative.

This new payload needs explicit database-specific approval before export under the earlier automatic approval review boundary. Profile data includes user accounts, password hashes, realm/signing configuration and identity/client credentials; blog/AIO data includes application and deployment records and may contain associated authorization data. These would become readable to sufficiently privileged Azure database/cluster administrators. This is a rehearsal copy, not a DNS switch or authority transfer. No production identity actions or email should be triggered while validating the clone.

## Readiness gates before any public record change

1. Restore and verify the three approved schemas, establish separate least-privileged target roles, and keep source write authority.
2. Bring up profile, blog and AIO with target-specific settings. Disable unintended SMTP/identity side effects during clone testing. Do not run two AIO worker sets against the same live GitOps/controller authority.
3. Establish durable AIO image-registry access; the prior temporary GAR token test was cleaned up and is not a production pull mechanism.
4. Prove exact-host OIDC redirects, real login and owner/non-owner access; resolve GCS/object-storage dependencies; satisfy build/security gates and browser checks. The Learn test still requires its recorded Nginx compatibility override or a repaired artifact.
5. Obtain scoped authoritative DNS access, provision the public Azure ingress endpoint and publicly trusted certificates, and validate routing without changing production DNS first. Public LB/IP usage has incremental Azure charges.
6. Agree a maintenance/write-freeze or tested replication procedure, take fresh data checkpoints (current copies are stale as source writes continue), rehearse final sync and data-safe rollback, then review exact old/new records and TTLs.
7. Change approved DNS records, observe application health and propagation, retain source resources through the rollback window, and transfer AIO/GitOps worker authority exactly once. Do not use the current cascading single-environment destination replacement as a preview switch.

Current outcome: private routing ready; Learn/homepage staging exists; profile/blog/AIO are not yet ready on Azure. The all-domain cutover condition is not yet met, so no DNS records have been changed.
