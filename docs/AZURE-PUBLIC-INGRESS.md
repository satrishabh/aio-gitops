# Public Azure ingress handoff

Provisioned with explicit user approval on 2026-11-14. **No DNS record was changed.** The selected all-domain-after-readiness cutover gate remains in effect.

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

For eventual DNS, this IP is the A-record target. Do not change the live root, Learn, profile, blog or AIO records yet: the user selected a coordinated switch after all applications are ready, and publicly trusted certificates/identity/data-finalization checks are still pending. `app.sarvaikam.com` and the other unmapped hosts currently have no Azure application route and must not be pointed here prematurely. Authoritative DNS is at Hostinger; the observed GCP zone is not proven delegated.
