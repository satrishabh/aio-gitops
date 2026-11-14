# Cilium network policy on AKS `creative` — 14 November 2026

## Outcome

- AKS now runs **Azure CNI powered by Cilium** (`networkDataplane: cilium`, `networkPolicy: cilium`).
  Before this, `networkPolicy` was `none`: no NetworkPolicy (including Argo CD's own) was enforced.
- Default-deny ingress policies applied: `ops/portable/azure-network-policies.yaml`.
- PriorityClass `aio-critical` on user-facing workloads + PostgreSQL: `ops/portable/azure-priorityclass.yaml`.
- AKS image cleaner (Eraser) add-on disabled to free CPU requests.

Verified after apply (test pods, 6 s TCP connect):

| From | To | Expected | Result |
| --- | --- | --- | --- |
| dev | postgres 5432 | open | open |
| aio-platform | postgres 5432 | open | open |
| ci | postgres 5432 | blocked | blocked |
| default | postgres 5432 | blocked | blocked |
| default | dev/sarvaikam:80 | blocked | blocked |
| default | aio-platform:80 | blocked | blocked |
| staging | dev/user-profile-manager:80 | blocked | blocked |
| dev | dev/user-profile-manager:80 | open | open |

All sites 200 through Traefik; all pods Ready (kubelet probes allowed); nightly backup job
(dump + Azure Blob upload) succeeded under the policies.

## Why it was disruptive: the subscription is a Free Trial

`quotaId: FreeTrial_2014-09-01`, spending limit on. Regional and Standard FASv6 quotas are 4 vCPU
(both nodes use all 4) and **cannot be raised on a trial** (`ResourceNotAvailableForOffer`).
Consequences: the cluster autoscaler cannot add nodes (set to min=max=2), AKS surge upgrades
(including the weekly node-image security upgrades) have no capacity, and system node pools
reject `maxUnavailable > 0`. The trial also **stops all resources** when it ends.

The Cilium change therefore ran by shrinking to one node (freeing 2 vCPU for the surge node).
Observed impact (331 probes, 22:08–22:53 IST):

| Endpoint | Outage windows |
| --- | --- |
| Profile (Keycloak) | 22:13:48–22:15:17 (node removed while its rollout had silently not moved it); three 13–25 s preemption windows 22:28–22:30; Cilium rounds 22:34–22:36, 22:39–22:42 |
| Learn API | Cilium rounds 22:34–22:37, 22:39–22:42 |
| Blog | 22:12 (one sample); Cilium rounds 22:34–22:37, 22:39–22:43 |
| AIO | Cilium rounds 22:34–22:36, 22:39–22:43 |
| Homepage | 22:11 (one sample); 22:35–22:36; 22:39–22:41 |
| Argo CD | 22:34–22:35; 22:39–22:42 |

Lessons: on a CPU-full single node, check **CPU requests**, not just memory; confirm each
workload actually moved before removing a node; giving pods a higher priority on a full node
can preempt other user-facing pods still at default priority.

## Follow-ups

1. **Upgrade the subscription to Pay-As-You-Go** before the trial ends; then raise South India
   vCPU quota to 8, set autoscaler 2–4 and maxSurge back to a surge-capable value.
2. Add `priorityClassName` support to `helm/app`, `helm/aio-platform` and Traefik values.
3. Optional next step: egress policies (currently open).
