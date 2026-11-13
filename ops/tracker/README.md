# Private e2-micro migration tracker

Provisioned on 2026-11-14 in project `project-d28840cd-be2f-4901-85e`: VM `aio-migration-tracker`, zone `us-central1-a`, machine `e2-micro`, 10GiB `pd-standard`, Debian 12, no external IP and no service account. Billing-account-wide free-tier usage must still be verified; free compute/disk eligibility is not a promise that all related services cost zero.

The dashboard publishes a read-only source inventory snapshot and lets an operator select a proposed Kubernetes target and download a draft checklist. It does not perform migration, traffic switching, credential retrieval or automatic cluster discovery. It has no application databases or cloud tokens. All draft cutover checks start false.

The service uses a systemd loopback socket and a small shell responder, with no package downloads or outbound Internet dependency. Hostname/route content is generated from an operator-approved snapshot. Paths are explicitly allowlisted. Dashboard files are root-owned/read-only to the unprivileged responder. The VM's tag-specific firewall permits IAP SSH and denies other ingress without changing existing GKE firewall rules.

## Access

Use your existing authorized Google identity and OS Login/IAP permissions:

```powershell
gcloud compute ssh aio-migration-tracker --project=project-d28840cd-be2f-4901-85e --zone=us-central1-a --tunnel-through-iap --ssh-flag=-L --ssh-flag=8088:127.0.0.1:8080 --command='sleep 3600'
```

Open `http://localhost:8088` while the SSH tunnel is running. Windows gcloud uses PuTTY/Plink, which requires `-L` and the forwarding value as separate arguments. The session used during setup has an explicit temporary SSH key; omit that option to use your normal key. The tunnel command exits after one hour and can be restarted. No public HTTP endpoint is exposed.

## Inventory publication

The initial source snapshot contains 35 Argo applications, captured at `2026-11-13T20:25:47Z` (2026-11-14 in Asia/Kolkata). Azure evidence was separately observed at `2026-11-13T21:16:46Z`: subscription `150909ba-4c23-44e9-b98d-e8a2d433f709`, resource group and AKS cluster `creative`, South India. Two nodes were Ready; HTTP, service DNS, disk writes and persistence after pod replacement passed. A later user-approved private AIO image pull and Java runtime check passed at `2026-11-13T21:25:30Z`; its temporary credential Secret and test namespace were deleted. Durable registry access and application/controller/data/traffic acceptance remain pending. The UI displays each evidence timestamp and pending checks without enabling execution. See `docs/AZURE-PREFLIGHT-2026-11-14.md`.

To refresh, gather a new authorized Argo inventory locally, exclude credentials and replace `/opt/aio-tracker/status.json` atomically through IAP with root ownership and mode 0644. This VM deliberately cannot poll clouds itself. Keep migration plans/evidence in Git or another backed-up system; the VM disk is not the sole source of truth.

Source files: `index.html`, `respond.sh`, `startup.template.sh`. Setup injects Base64 versions of the static page, snapshot and responder into the startup template; Base64 here is transport encoding, not encryption, so never include credentials. Startup metadata is visible to authorized project/instance administrators.

Verified: VM running with expected type/disk and no external IP/service account; socket active/enabled; private tunneled HTTP 200; dashboard title and JSON inventory of 35 applications. Azure connectivity and infrastructure smoke were verified by a separate authenticated operator session, then published here. This does not grant the VM cloud access or complete application readiness, data transfer or cutover.
