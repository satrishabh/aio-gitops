# Homepage live on Azure — 2026-11-14

The user explicitly revised the earlier coordinated-cutover plan: start with the homepage, and reported changing the apex A record. `sarvaikam.com` now resolves locally to Azure static ingress `52.140.55.184`. The assistant did not change DNS records. Other application domains remain on their previous destinations.

## Routing and certificate repair

HTTP already returned the correct homepage HTML. HTTPS failed because the canonical homepage route was still using the earlier explicitly self-signed routing-test certificate.

The homepage now has its own Ingress and production Let's Encrypt certificate, `aio-validation/homepage-public`, Secret `homepage-public-tls`. `azure-home-public.yaml` uses a namespaced HTTP-01 Issuer with the existing operator ACME contact and ingress class `aio-azure`. The self-signed rehearsal route/certificate was narrowed to Learn only, preventing conflicting homepage TLS selection.

Google and Cloudflare recursive DNS still returned the old GCP address `34.180.3.18` with a four-hour TTL during issuance. A temporary GCP Service/EndpointSlice/Ingress proxied only the active ACME challenge path to Azure, allowing validation from stale DNS paths. No normal page traffic or private key was changed/copied by the bridge. All three bridge resources were deleted after certificate issuance.

## Verified result

- Direct Azure HTTPS using canonical SNI/Host and normal system certificate verification: HTTP 200.
- Normal-DNS `https://sarvaikam.com`: HTTP 200, 8730-byte homepage response.
- Certificate Ready, expiry `2027-02-12T04:48:58Z`, scheduled renewal `2027-01-13T04:48:58Z`.
- Production TLS validation did not disable verification or supply a self-signed trust override.
- HTTP remains available for HTTP-01 renewal; a forced HTTP-to-HTTPS redirect was not added in this fix.

DNS propagation can leave some clients reaching the retained GCP homepage temporarily. Keep that source route available through the propagation/rollback window. Public resolvers will converge as cached records expire. `www.sarvaikam.com` still CNAMEs to `www.sarvaikam.com.cdn.hstgr.net` and is outside this apex-only certificate. It needs a separately coordinated DNS/routing/certificate update if the alias is to move.

Learn retains its separate rehearsal configuration and is not publicly cut over. Profile, blog and AIO are not ready on Azure. The remaining database-copy approval and application-readiness gates still apply.
