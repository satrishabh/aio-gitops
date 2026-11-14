# Cloud migration runbook

Reusable draft, prepared 14 November 2026. Applies to AIO, its managed
applications and their dependencies. Use this for the next migration in either
direction, including Azure to another provider. Adapt and rehearse the plan
before execution. This document does not execute a migration.

## 1. Current baseline and limits

Recorded outcome of the GCP-to-Azure migration on 14 November 2026:

| Component | Recorded destination/state |
| --- | --- |
| Homepage, Learn, Profile Manager, Blog, AIO | Native Azure AKS `creative`, South India |
| Public ingress | Azure `52.140.55.184`; Hostinger DNS |
| Database write authority | Azure PostgreSQL, one database `aio_rehearsal`, five application schemas |
| TLS | Azure cert-manager with public Let's Encrypt certificates; private database TLS |
| GCP GKE | Zero workers; cluster/control plane retained |
| GCP Cloud SQL `aio-002` | Stopped; source data and backups retained |
| Old GCP ingress | Public load balancer retired; old ephemeral IP released |
| Independent tracker | GCP e2-micro, private access, operator-published snapshots |
| Delivery automation | AIO background workers paused pending native GitOps mapping |

This is a dated baseline, not live discovery. Verify it before future work.
The earlier temporary Azure-to-GCP proxy was not a native migration; it was
removed during cutover. The later GCP-to-Azure cached-DNS forwarder was also
retired when the user chose zero GKE workers. Do not assume the old IP
`34.180.3.18` is available or belongs to this project.

Completed HTTP/TLS checks do not prove authenticated browser workflows or a
complete Azure build-to-release cycle. Those acceptance checks remain required.
The tracker is a planning/observation UI, not a cloud migration execution engine.
Provider profiles and a cloud dropdown do not establish a working adapter.

## 2. Create one migration record

Copy this table into a dated change record. Identify people by role/name and
store credentials only in the approved secret store, never in this record.

| Field | Fill before execution |
| --- | --- |
| Migration ID and plan revision | Unique ID; reviewed Git commit |
| Scope | Apps, environments, hub, databases, files, jobs, domains |
| Mode | One-way move, disaster-recovery rehearsal, or designed multi-cloud operation |
| Source | Provider, account/project/subscription, region, cluster/context |
| Target | Provider, account/project/subscription, region, cluster/context |
| Owners | Migration lead; data, application, identity, DNS and rollback owners |
| Access | Operator identity, required roles, secret references and expiry |
| Budget | Temporary overlap, compute, disks, databases, load balancers, egress, backups |
| Recovery point objective (RPO) | Maximum acceptable data loss; define per service |
| Recovery time objective (RTO) | Maximum acceptable recovery interruption |
| Window | Start/end with timezone; expected write freeze; communication contact |
| Data method | Snapshot under write freeze, rehearsed replication, or designed shared service |
| Traffic method | DNS, ingress, load balancer or CDN; propagation and caching limits |
| Rollback | Conditions, decision deadline, reverse-sync method, retained resources |
| Retention | Source rollback window; snapshot and evidence retention dates |
| Acceptance | Exact tests, owners and evidence required for completion |

A maintenance-window snapshot can preserve committed data if every writer is
stopped and every required database/file source is captured. It does not provide
zero downtime or automatic cross-database consistency. For shorter interruption,
design and rehearse replication separately, including sequence state, schema
changes, lag and write fencing. Do not claim this migration implements replication.

## 3. Inventory the entire dependency graph

Capture desired and observed state before changing it:

- Application image digests, source commits, architecture, ports, probes,
  resource requests/limits, replicas, scheduled jobs and background consumers.
- Kubernetes namespaces, Helm releases, Argo applications, RBAC, controllers,
  admission rules, CRDs and the tools that can recreate stopped workloads.
- Every database, schema, extension, sequence, function, owner, grant, connection
  pool, external writer and migration-history table.
- Files and object references: buckets, volumes, attachments, signed URLs,
  encryption/KMS dependencies, retention policies and external content providers.
- Identity realms, client IDs, confidential-client secret references, callback
  URLs, issuer URLs, role claims, session behavior, email and identity providers.
- DNS zone authority, A/AAAA/CNAME records, apex and `www`, TTLs, CDN/proxy mode,
  certificates, renewal method and origin TLS validation.
- Registry pull/push access, Git repository access, webhooks, scanners, package
  repositories and third-party APIs or allowlists.
- Existing backups, restore evidence, metrics/logs, alerts and operator access
  that remain available if the source cloud is shut down.

Record secret names, keys, purpose and owners only. Keep original replica counts
and nonsecret configuration snapshots. Preserve unrelated work and use an
isolated branch/worktree for the migration.

**Gate:** every stateful dependency and potential writer has an owner and a
transfer/freeze method. A green pod alone is not sufficient.

## 4. Authenticate and prepare the target

1. Select the correct tenant/account and verify subscription/project access.
   Inspect cluster permissions, API reachability and workload identity separately.
   Successful cloud sign-in does not prove Kubernetes access.
2. Retrieve a separate, private kubeconfig. Use explicit context/kubeconfig flags
   for both clouds; never rely on a default context during migration.
3. Respect tenant sign-in policy. A blocked device-code/CLI flow requires an
   allowed login method or administrator resolution, not policy bypass. Record
   which method actually succeeds; historical attempts are not reusable tokens.
4. Check node capacity, image architecture, registry/Internet egress, private
   database connectivity, DNS, CSI/storage classes and zone placement.
5. Review storage reclaim policy, volume expansion, backup and restore behavior.
   A single PostgreSQL pod on one disk is not a high-availability database.
6. Install only missing, reviewed controllers. Pin versions/digests; avoid
   duplicate controller ownership. Test cert-manager, ingress, Argo and Tekton
   independently. Install Rollouts before managing Rollout resources.
7. Establish target-specific runtime Secrets, database roles and trust bundles.
   Give each app access only to its schema and required services.
8. Separate build push, repository write, runtime pull and deployment API access.
   Validate credential scope, storage, expiry and rotation. Copying credentials
   to another namespace/service must remain within the authorized destinations.
9. Configure the target AIO standby with background workers disabled. Prevent
   startup reconciliation, scheduled jobs and consumers from becoming writers.

**Gate:** target access, controller ownership, durable storage, registry pull,
database TLS and sufficient bounded capacity are demonstrated.

## 5. Stage application images and runtime configuration

Use exact image digests. Mirror images when required and verify digest equality;
do not assume a pull from a temporary credential will remain valid in production.
Keep registry credentials scoped to their host. Preserve build and scan gates.

Configure ingress class, storage, workload identity, database URLs, trust roots,
schema names, OIDC settings and service discovery through reviewed values.
Remove source-specific socket factories, metadata dependencies and localhost
issuers unless the target deliberately provides them. Bound connection pools
against the database connection limit, including rolling-update surge pods.

Use private or isolated validation routes. Do not route live users to an old
database clone. Do not change existing environment-to-cluster mappings just to
preview another target: current reconciliation may delete/recreate applications.

Verify startup using the actual image entrypoint. For a migrated identity realm,
avoid unintended bootstrap/import behavior. Keep any temporary compatibility
override explicit, versioned and accompanied by a plan to replace it with a
corrected image. Check immutable Deployment selectors before GitOps adoption.

**Gate:** matching images start on the target using target services; source
runtime dependencies are either removed or explicitly documented.

## 6. Rehearse the data migration

1. Take a source backup and restore it to an isolated target. A successful
   backup job is not proof that its restore works.
2. Use a compatible database client. In this migration, source PostgreSQL 18
   required PostgreSQL 18 export tooling; the app image's older client failed.
3. Keep source database credentials source-local. Temporary export clients may
   reference source Secrets; do not transfer source DB passwords to the target.
4. Use encrypted paths and bounded streaming or encrypted archives with an
   explicit retention policy. Do not print records, passwords or signed URLs.
5. Define database/schema mappings explicitly. The completed Azure move used:

| Application/source database | Azure database | Target schema |
| --- | --- | --- |
| Learn `sarvaikam` | `aio_rehearsal` | `sarvaikam` |
| Reels `sarvaikam_reels` | `aio_rehearsal` | `sarvaikam_reels` |
| Identity `profile_manager` | `aio_rehearsal` | `profile_manager` |
| Blog `sarvaika_blog` | `aio_rehearsal` | `sarvaika_blog` |
| AIO `aio` | `aio_rehearsal` | `aio_platform` |

6. Inspect extensions, schema-qualified defaults, functions, large objects,
   permissions and schema collisions. Rename actual PostgreSQL objects rather
   than globally rewriting dump SQL or data. This project's scripts intentionally
   reject unsupported source layouts; they are not universal database converters.
7. Reapply application ownership/grants and explicit search paths after restore.
   Validate sequence ownership/state and migration history. Test cross-schema
   denial with the actual application roles.
8. Compare row counts and content fingerprints against the same exported source
   snapshot. Record only sanitized match results. For large datasets, use a
   scalable verification design rather than unbounded in-memory aggregation.
9. Inventory and copy referenced objects/files, checking checksums and read
   access. Zero external object references in this migration does not mean a
   future migration can skip object storage.
10. Test restart, persistent storage recovery and target backup restore. Set up
    continuing target backups and verify them before accepting the operational
    handoff; source backups do not protect new target writes.

**Gate:** restore works, data matches, permissions are correct and recovery
objectives are supported by evidence.

## 7. Accept the staged target

| Area | Required checks |
| --- | --- |
| Runtime | All required pods Ready; restart behavior; no unexplained crash loops |
| Database | Verified TLS/hostname, schema ownership, bounded pools, persistence |
| APIs | Health/readiness; expected data; anonymous and forbidden requests denied |
| UI | HTML/assets plus a real browser check; navigation and error states |
| Identity | Real login/logout/callback; roles; owner/non-owner access; session effects |
| Files | Existing references resolve; upload/read/delete permissions; checksums |
| Delivery | Fresh build, tests, scan, GitOps commit, rollout and status in AIO |
| Certificates | Hostname/SAN, trusted chain, issuer, expiry and renewal routing |
| Operations | Metrics, alerts, logs, operator access, backup and restore evidence |

For Learn, the existing HTTP helper checks health, APIs, UI assets and anonymous
denial. It does not perform browser or authenticated identity acceptance. Record
unperformed tests as pending rather than inferring success from HTTP 200.

## 8. Prepare DNS and certificate cutover

Lower the relevant DNS TTL sufficiently before the window to let the previous
TTL expire. Updating TTL at cutover does not clear existing cached entries.
Inspect authoritative delegation and query every authoritative nameserver plus
independent recursive resolvers. Check IPv6 and `www` separately from the apex.

For this Hostinger setup, `@` is the apex A record and application subdomains
have their own records. `www` used a separate Hostinger CDN CNAME and is not
automatically migrated by changing `@`. Provision the target `www` route and
certificate before changing its DNS record.

Issue trusted target certificates before publishing application traffic. Verify
ACME challenge paths and the ingress provider's namespace/class configuration.
Application router priority and HTTPS redirects must not intercept renewal
challenges. Distinguish staged/self-signed certificates from public certificates.

If using a temporary old-IP forwarder, retain original Host, correct forwarded
scheme and CA/SNI verification. Allow only intended hostnames and a fixed origin;
do not create an open proxy. Plan its retirement and associated load-balancer cost.
An issuer name alone does not prove where the application or database runs.

**Gate:** target TLS passes direct-IP/SNI tests, DNS change scope is known and
old-cache behavior is explicitly accepted or covered by a forwarder.

## 9. Freeze, final sync and transfer write authority

Communicate the maintenance window and named rollback owner. Then execute this
reviewed sequence; stop on a failed gate:

1. Stop new releases and drain active builds/deployments or record an explicit
   decision for each active run. Save source/target desired replica counts.
2. Pause source AIO reconciliation and source GitOps automation so they cannot
   recreate writers. Include scheduled jobs, queue consumers and external writers.
3. Drain source application writers and target standby writers. Confirm both
   desired and observed replicas and remaining database connections.
4. Confirm a fresh successful source backup and an available restore path.
5. Retain the previous target data as a named backup, then run the final sync.
   Check target staging schemas are safe before restoring. The UUID extension
   must be handled correctly when refreshing consolidated schemas.
6. Verify every required snapshot and restore grants. Keep source writers fenced.
7. Start identity first. Its canonical issuer must reach the target through all
   DNS paths used by dependent services. Then start dependent APIs, Blog, Learn
   and AIO. Wait for each dependency rather than starting everything blindly.
8. Activate native ingress/traffic routes and update DNS/CDN as planned. Test the
   actual target backend, not just the proxy edge. Retire opposite-direction
   forwarding so routes cannot form a loop.
9. Declare the new write authority, record its first accepted writes and prohibit
   automatic reactivation of the old writers.

**Commit point:** once target writes are accepted, the old database is stale.
DNS reversal alone is no longer a safe data rollback.

## 10. Observe, complete GitOps ownership and enable delivery

Check public HTTPS over normal DNS, explicit target IP/SNI and any retained
old-IP forwarder. Record which paths were tested. After source hibernation,
do not require or report successful old-IP checks.

Observe application errors, login, write/read-back behavior, pool saturation,
latency, persistent storage, external integrations and data freshness over the
agreed window. Rehearse the approved rollback procedure in an isolated setting.

Map each project/environment to its intended cluster, namespace, Argo application,
values file, ingress, database role/schema and registry identity. Preserve
intentional zero replicas. Reconcile the existing native resources without
immutable-selector conflicts or unintended pruning. Avoid two controllers
managing the same resources.

Keep AIO background workers disabled until native GitOps ownership/mapping and
a fresh end-to-end release have passed. Then enable only the chosen management
authority and verify the UI reflects observed target state. Complete remaining
authenticated/browser acceptance and backup checks before closing the handoff.

## 11. Rollback decision table

| Situation | Required response |
| --- | --- |
| Before cutover; target has accepted no authoritative writes | Keep source traffic/data authoritative; stop target writers; restore source service state only after confirming its data is current |
| Target has accepted writes | Fence target and source writers; capture target backup; reconcile/reverse-sync data; validate; only then switch traffic and resume source |
| Target unavailable and latest writes cannot be recovered | Follow the agreed disaster-recovery RPO decision; quantify potential loss; never silently present stale source data as current |
| Source compute is stopped | Restore capacity and database access while keeping app writers/reconcilers paused; complete data checks before activation |
| Old source public IP was released | Provision a new route/IP and update DNS/TLS; do not depend on recovering the historical address |

Application-image rollback and data rollback are different operations. Confirm
schema compatibility before running an older image against a newer database.
Do not restore a backup over a live authoritative database without a new freeze.

## 12. Downgrade or retire the source

After acceptance and the agreed rollback window, inventory charges again.
Treat hibernation and permanent deletion as different actions.

| Resource | Reversible cost reduction | Permanent action needing an explicit retention/deletion decision |
| --- | --- | --- |
| Kubernetes workers | Scale pool to zero; disable unwanted scale-up | Delete cluster/control plane and its configuration |
| Database compute | Stop where supported; verify actual stopped state | Delete database instance and retained data |
| Public load balancer | Retire unused ingress/LB after old-cache decision | Release addresses; do not assume an ephemeral IP is recoverable |
| Persistent disks | Retain required rollback data; classify CI/cache disks | Delete only identified disposable data or expired retained copies |
| Images/buckets/backups | Apply reviewed retention/lifecycle rules | Delete historical versions after recovery requirements are met |
| Credentials/webhooks | Revoke unused identities and route webhooks to target | Remove obsolete integrations after confirming no target dependency |
| Independent tracker | Retain minimal private instance if still required | Retire only when observation/history has another owner |

Stopping worker/database compute does not make the cloud account free. Check
control-plane fees, storage, backups, addresses, load balancers, logging, registry
objects and egress. Verify cloud operations completed; an accepted API request
does not prove resources stopped. Check billing after its reporting delay.

## 13. How to use the current script collection

**These are migration-specific operator helpers, not a generic orchestration API.**
Several hardcode the original GCP source, Azure target, names and old IPs.
Review and adapt them for a new run. Do not rerun the original freeze/final sync
against today's live Azure database. Some helpers intentionally refuse overwrite.

| Helper | Purpose / boundary |
| --- | --- |
| `render.py` | Generate provider installation values/preflight plans; does not provision clouds |
| `audit-remaining-databases.py` | Source-local metadata audit; temporary export-client lifecycle |
| `stream-schema-rehearsal.py` / `migrate-remaining-schemas.py` | Initial snapshot copies into reviewed schemas; not live replication |
| `prepare-native-runtime.py` | Fresh target roles and target runtime setup; not a general rotation tool |
| `mirror-aio-image.py` | Host-scoped image copy and digest verification |
| `configure-native-delivery.py` | Specifically authorized target Argo/CI credential setup |
| `check-learn-http.py` | Read-only Learn API/UI-asset checks, not browser login |
| `native-cutover-control.py` | Save replica metadata, freeze original sides, start native apps; rollback-source action intentionally refuses automatic restart |
| `final-schema-sync.py` | Guarded final snapshots after verified drain; retains previous schemas |
| `activate-native-routes.py` | Original Azure route activation; identity-first mode; historical GCP forwarder references must be reviewed |
| `record-native-observation.py` | Sanitized evidence/tracker snapshot; `--gcp-hibernated` skips intentionally retired old-IP paths |
| `hibernate-gcp-management.py` | Plan-only by default; execution requires explicit old-DNS interruption/IP-release acknowledgement |

Database backup schemas are local retained copies, not independent durable
backups. Use separate verified backups as well. Public database trust-certificate
rotation requires refreshing client trust and verifying/reloading services.
Never publish populated runtime Secrets, tokens, dumps or private keys in Git,
tracker snapshots, startup metadata or command output.

## 14. Provider adaptation checklist

The application contract is Kubernetes, PostgreSQL, OIDC, registry and HTTPS.
Infrastructure, identity, storage, DNS and credential lifecycle need provider
adapters and separate acceptance evidence.

| Target | Adapt and test |
| --- | --- |
| Azure AKS | Subscription/Entra/RBAC, AKS identity, CSI, ingress/public IP, registry and DB networking |
| AWS EKS | IAM, cluster access, storage/LB controllers, registry credential renewal, DB networking |
| Alibaba ACK | RAM, cluster access, regional registry/egress, CSI/LB and DB integration |
| GCP GKE | Workload Identity, registry access, storage/ingress, DB connectivity and source reactivation guards |
| Other Kubernetes | Conformance, storage, external ingress, identity, registry, backups and operations |
| Cloudflare DNS/CDN | DNS/proxy/origin TLS and challenge behavior in front of an accepted runtime |
| Cloudflare edge runtime | Separate execution adapter and application compatibility work; not provided by the current Helm/Kubernetes adapter |

True multi-cloud active/active operation additionally needs a designed data
consistency model, session/key handling, background-job fencing, routing and
failure recovery. Deploying the same image twice is not sufficient.

## 15. Future migration UI and tracker requirements

Treat these as a product backlog, not features already implemented:

1. Cloud accounts with child account/subscription/project entries; role-scoped
   secret references and access/expiry checks, never secret values in the UI.
2. Discovered cluster/application/dependency inventory and a separate proposed
   destination. Show observation timestamps and stale/offline state.
3. Versioned migration plans with image digests, data method, RPO/RTO, costs,
   DNS/TLS plan, rollback boundary and retention dates.
4. Persisted stages: Draft, Preparing, Rehearsing, Validated, Scheduled, Freezing,
   Synchronizing, Switching, Observing, Completed, Failed and Rolling back.
   Store the current write authority separately; it must never be ambiguous.
5. Certificate inventory showing host, cluster, issuer, expiry, renewal state,
   current serving route and challenge errors, without exposing private keys.
6. Durable job execution with locking, retries, audit events and resumable
   checkpoints. Separate plan-only actions from actions that change resources.
   Use short-lived scoped runner identities rather than long-lived cloud keys
   embedded in the e2 tracker or scripts.
7. Show observed serving state separately from desired routing. Make clear when
   a CDN/proxy reaches the other cloud, or when a source has been hibernated.
8. Enable a cloud switch only after its access, target, data, identity, TLS and
   rollback gates pass. Data-aware rollback must never be a blind DNS toggle.

## 16. Closeout checklist

- [ ] Scope, owners, budget, RPO/RTO and maintenance window recorded.
- [ ] Every dependency, writer and controller inventoried.
- [ ] Source backup restored successfully in rehearsal.
- [ ] Target storage, roles, TLS, identity and credential lifecycle tested.
- [ ] Images pinned; object/file references migrated or explicitly retained.
- [ ] Authenticated UI and owner/non-owner access accepted.
- [ ] Source automation fenced and final snapshot/replication checkpoint verified.
- [ ] Exactly one write authority recorded; target read/write tests pass.
- [ ] DNS, apex, `www`, IPv6, TLS and cached-client behavior checked.
- [ ] Native GitOps mapping and a new build-to-release cycle accepted.
- [ ] Target backups/restores, alerts and operator access handed over.
- [ ] Rollback procedure, retention window and decision owner recorded.
- [ ] Source downgrade verified; deletion decisions handled separately.
- [ ] Tracker/UI, Git commit, evidence and remaining limitations published.

## Related records

- [Native Azure cutover](NATIVE-AZURE-CUTOVER-2026-11-14.md): event record;
  read the subsequent hibernation record for the final source-ingress state.
- [GCP downgrade and final hibernation](GCP-DOWNSIZE-2026-11-14.md).
- [Final schema verification](evidence/native-final-schema-sync.json).
- [Latest public acceptance observation](evidence/native-public-cutover.json):
  latest snapshot; earlier evidence versions remain in Git history.
- [Provider installation/configuration process](MULTI-CLOUD-PROCESS.md).
- [Migration switch design](MIGRATION-SWITCH-PLAN.md).
- [Tracker setup and access](../ops/tracker/README.md).
