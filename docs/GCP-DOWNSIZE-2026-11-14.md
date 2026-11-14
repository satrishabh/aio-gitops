# GCP resource reduction after Azure cutover

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
