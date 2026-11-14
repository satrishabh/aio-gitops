# Public Azure ingress handoff

Provisioned with explicit user approval on 2026-11-14. No DNS record was changed during endpoint provisioning. Subsequently the user selected homepage-first and changed the apex A record; [homepage HTTPS is now verified live](HOMEPAGE-AZURE-LIVE.md). Remaining domains still wait for their readiness gates.

| Setting | Value |
| --- | --- |
| Static public IPv4 | `52.140.55.184` |
| Public ports | TCP 80, 443 |
| Azure resource | `aio-ingress-public-ip` |
| Resource group | `MC_creative_creative_southindia` |
| Subscription | `150909ba-4c23-44e9-b98d-e8a2d433f709` |
| Kubernetes service | `aio-ingress/aio-azure-ingress-traefik` |
| IngressClass | `aio-azure` |
| Helm release/revision | `aio-azure-ingress`, revision 2 |

The Standard IPv4 is separately allocated and static. Public IPv4 and load-balancer usage have incremental Azure charges; retaining the IP keeps it allocated independently of a temporary application restart. Application databases, controllers and dashboards are not directly exposed by this service.

Apply both `ops/portable/azure-ingress-values.yaml` and `ops/portable/azure-ingress-public-values.yaml` to reproduce the public service. The public overlay uses Azure's named-PIP/resource-group annotations so the same address is selected. Omitting that overlay restores the private base configuration.

Public-IP tests passed with DNS overridden locally, not changed globally: Learn `/ready` and reels returned 200, homepage returned 200, and an unmapped host returned 404. These TLS tests explicitly trusted the existing self-signed test certificate. It is **not browser-trusted production TLS**. Structured evidence: `evidence/azure-public-ingress.json`.

The user has now set the root A record to this IP, and the root homepage has a separate production certificate. Leave Learn/profile/blog/AIO records unchanged until their applications, identity and final data checkpoints are ready. `app.sarvaikam.com` and other unmapped hosts have no Azure route yet. Authoritative DNS is at Hostinger; the observed GCP zone is not proven delegated.

Later user DNS edits moved additional hostnames. Trusted transition routes now exist for Learn/profile/blog/AIO, backed by retained GCP services; see [current serving topology](TRANSITION-DOMAINS-LIVE.md). These routes avoid exposing stale rehearsal data and do not complete the pending native migration.
