# GCP resource reduction after Azure cutover

## Final hibernation state

The user subsequently explicitly selected **Scale GKE to zero now**, accepting
interruption for clients retaining old GCP DNS entries. All five canonical
domains were confirmed on Azure at both Hostinger authoritative nameservers.

At `2026-11-14T08:21:10Z`, zero-node resize operation
`operation-1794644272426-1ec6286b-5873-41dd-8050-36d3e7dab9bc` completed.

- GKE has **zero worker nodes**; the cluster configuration/control plane remains.
- Cloud SQL `aio-002` remains **STOPPED**, activation policy **NEVER**.
- Source Argo, Rollouts, Tekton, certificate management, DNS controller, ingress
  and legacy forwarder deployments were scaled to zero. Original replica counts
  were saved as nonsecret operator metadata before changes.
- The obsolete GCP ingress Service was changed from LoadBalancer to ClusterIP.
  Its forwarding rule, target pool and ephemeral public IP were released; no
  GCP forwarding rules or reserved addresses remained in the inspected project.
  **Do not assume `34.180.3.18` can be recovered or still belongs to this project.**
- Only the requested **e2-micro tracker VM** remains running among Compute VMs.
- Application data, persistent disks, certificates and backups were retained.

All five native Azure sites passed trusted HTTPS checks after hibernation.
Normal DNS clients still caching the released GCP IP may fail until expiry.
The tracker UI/snapshot records hibernation rather than promising old-IP forwarding.

This is not a zero-cost project. Retained storage/backups, registry objects, the
GKE control plane and tracker resources may still incur charges or consume free
tier allowances. No database, persistent disk or cluster was deleted.

The source hibernation helper defaults to plan-only and requires explicit
execution plus acknowledgement of old-DNS interruption/IP release. Restoring
source applications requires reverse data synchronization first; resizing the
cluster alone must not reactivate old database writers.

## Earlier reduction

Completed on 14 November 2026, at the user's request:

| Resource | Before | After |
| --- | --- | --- |
| GKE `cluster-1`, `highmem-pool` | Two `e2-highmem-2` nodes | One Ready `e2-highmem-2` node |
| Cloud SQL `aio-002` | RUNNABLE, activation ALWAYS | STOPPED, activation NEVER |
| Source Cloud SQL proxies | Two running deployments | Both scaled to zero |
| Migration tracker | Running `e2-micro` | Retained running |

GKE resize operation `operation-1794642361275-2da5be03-4429-429d-8752-4cb5cfe7036f`
completed successfully at `2026-11-14T07:47:41Z`. The retained node is
`gke-cluster-1-highmem-pool-a8391b3f-frrx`.

Source databases and backups were retained. Azure remains the application and
database write authority. The remaining GKE node runs the old-IP ingress and
HTTPS forwarder for cached-DNS clients, along with the retained management
components. Source application writers and Argo reconciliation remain paused.

This reduces GKE worker compute capacity by half and stops source Cloud SQL
instance compute. It does not eliminate GCP charges: retained database/disk/backup
storage, GKE management, networking and other provisioned services can still bill.
No claim is made that the tracker or retained resources are entirely free.

Restarting Cloud SQL is an operator rollback preparation step only. Do not restart
source application writers after Azure has accepted writes without a reverse
data synchronization. Restoring GKE capacity to two nodes is reversible.
