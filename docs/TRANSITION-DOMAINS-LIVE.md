# Additional domains restored through Azure edge — 2026-11-14

The user reported updating additional DNS records. Observed locally: Learn, profile and blog resolve to Azure `52.140.55.184`; AIO still resolved to GCP locally, so its Azure route was also prepared for propagation. `www` remained on Hostinger CDN. A later direct query to the authoritative nameserver confirmed all four Learn/profile/blog/AIO A records point to Azure; local AIO answers were cached.

Only homepage and an isolated Learn rehearsal existed on Azure. The rehearsal has stale snapshots and intentionally disconnected production identity, so it must not become the live application just because DNS moved. Profile and blog had no Azure backends. To restore service without copying unapproved identity/application databases, an Azure transition proxy forwards the canonical Learn/profile/blog/AIO hosts to the retained GCP ingress.

## Actual serving topology

| Host | Edge | Application / write authority |
| --- | --- | --- |
| sarvaikam.com | Azure, trusted Let's Encrypt | Native Azure static homepage |
| learn.sarvaikam.com | Azure, trusted Let's Encrypt | Existing GCP Learn and Cloud SQL |
| profile.sarvaikam.com | Azure, trusted Let's Encrypt | Existing GCP identity service and Cloud SQL |
| blog.sarvaikam.com | Azure, trusted Let's Encrypt | Existing GCP blog and Cloud SQL |
| app.sarvaikam.com | Azure route ready; DNS may still be cached on GCP | Existing GCP AIO hub and workers |

This is an encrypted transition edge, **not completed backend/database migration**. Keep GCP running. The Learn rehearsal is reachable only through its private service and Kubernetes port-forward. Its test-host Ingress was removed from the public controller; a public request for that host returned 404. Live users do not read/write its snapshot data.

`ops/portable/azure-transition-edge.yaml` pins the tested proxy image, allowlists exactly four hostnames, disables request access logs, redirects HTTP to HTTPS and forwards to GCP `34.180.3.18` over verified TLS with the original Host/SNI. It preserves cookies and forwarded protocol/host for existing identity callbacks. Upstream SSL session reuse is disabled to prevent sharing cached certificate identities across different hostnames on one origin IP. A mismatch was detected by actual tests and resolved without disabling verification.

The new `transition-domains-public` certificate covers the four transition hostnames, issued by the existing production Let's Encrypt Issuer. Certificate/private-key handling remains in cert-manager; no source TLS private key or database credential was copied. The source challenge-only bridge cleanup was checked; no temporary bridge resources remain.

## Verified checks

Canonical-host requests forced to the actual public Azure IP, with normal system TLS verification:

- Learn `/ready`: 200.
- Profile realm OIDC discovery: 200.
- Blog `/`: 200.
- AIO `/login`: 200.
- Profile HTTP redirects 308 to HTTPS.

The edge also verifies the GCP peer certificate/hostname. Evidence: `evidence/transition-domains-live.json`. These checks verify routing, health/discovery and login-page availability; they do not claim that an authenticated browser login or write workflow was exercised.

## Remaining migration

Copying `profile_manager`, `sarvaika_blog` and `aio` records into Azure schemas still needs the previously requested explicit data-transfer approval. No such copy was performed in this repair. Native profile/blog/AIO deployments, target identity/browser checks, storage resolution, security gates, final synchronized checkpoints and single AIO worker authority remain pending. Only then replace each transition backend with its validated native Azure service. The assistant did not make the user's DNS edits.
