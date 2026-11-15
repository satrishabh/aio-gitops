# AIO v2: industry-standard scorecard

Scored on 16 Nov 2026. **Before** = what runs live today (`main`). **Now** = branch `feat/aio-v2-saga-core`
(built and tested, **not deployed**). **Target** = after the roadmap below.

Scale: 0 absent · 1 ad hoc · 2 basic · 3 solid for one team · 4 matches the reference practice · 5 matches it with automation and evidence.

| # | Area | Reference standard / practice | Before | Now | Target | What moved it / what is missing |
|---|---|---|---|---|---|---|
| 1 | Workflow orchestration | Saga pattern (microservices.io), Temporal-style durable execution | 1 | 4 | 5 | Saga engine with leases, compensation, timeouts. Missing: release and provisioning still not sagas. |
| 2 | Idempotency and concurrency | Idempotent consumer, optimistic locking | 1 | 4 | 5 | Webhook inbox, same-commit dedup, atomic approval claim, `@Version`. Missing: transactional outbox for outgoing events. |
| 3 | Deployment verification | Argo Rollouts analysis, Harness continuous verification | 1 | 3 | 5 | DONE now means Synced, Healthy and running the new image, plus HTTP smoke samples with a failure limit. Missing: metric-based (Prometheus) analysis, canary traffic steps. |
| 4 | Failure strategies | Harness failure strategies, manual intervention | 1 | 4 | 5 | Per-environment rollback or pause; retry, skip, rollback, resolve; auto-rollback when nobody decides. Missing: notifications (mail or chat) when a deployment pauses. |
| 5 | Change governance | Four-eyes approvals, freeze windows (ITIL change management, Harness freeze) | 3 | 4 | 5 | Freeze windows with audited admin override; execution timeline. Missing: `APPROVERS` is empty in prod; policy as code (OPA). |
| 6 | GitOps | OpenGitOps principles v1.0 | 4 | 4 | 5 | Declarative, versioned, pulled, reconciled. Missing: two writers per values file (legacy `update-gitops` task and the platform). |
| 7 | Event-driven, no polling | CloudEvents, event-driven architecture | 1 | 2 | 4 | Saga worker sleeps until due. Missing: CI and deployment status still polled every 4–15 s; Tekton CloudEvents not used yet. |
| 8 | Scale-to-zero and FinOps | FinOps Framework (inform, optimise, operate) | 2 | 4 | 5 | Single node, Sablier, nightly hibernate, webhook buffer, idle UI pause, keep-awake lease: about US$280–330/mo → US$115/mo. Missing: builds on GitHub Actions and the Central India move (floor about US$55–60/mo). |
| 9 | Software supply chain | SLSA v1.0, Sigstore | 2 | 2 | 4 | Trivy scans, digest pins, trust policy designed. Missing: image signing, SBOM, provenance verification. |
| 10 | Observability | OpenTelemetry, SRE SLOs | 2 | 3 | 4 | Audit log, saga event log, execution timeline. Missing: metrics endpoint, traces, SLO alerts. |
| 11 | Identity and secrets | OWASP ASVS, least privilege | 3 | 3 | 5 | GitHub SSO, CSRF, default-deny network policy. Missing: GitHub App instead of a personal token; webhook secrets encrypted or in Key Vault. |
| 12 | Delivery metrics | DORA four keys | 3 | 3 | 4 | Deploy frequency, lead time, change failure rate, recovery time computed in-app. Missing: auto-reverts and pauses counted as failures; incident-based recovery time. |
| 13 | Backup and recovery | RPO / RTO practice | 3 | 3 | 4 | Nightly verified Postgres backups to Blob. Missing: scheduled restore drill, point-in-time recovery. |
| 14 | Test safety net | Test pyramid, migration testing | 3 | 4 | 5 | 159 backend tests, 5 UI tests. Missing: migrations against real PostgreSQL (Testcontainers), end-to-end tests. |
| | **Average** | | **2.1** | **3.4** | **4.6** | |

## Roadmap and expected score

| Step | Contents | Areas lifted |
|---|---|---|
| Ship v2 (this branch) | Run V10/V11 against a copy of the database, then deploy per `AIO-V2-PHASE1.md`. Set `APPROVERS`. | Live 2.1 → 3.4; governance 4 → 5 |
| Phase 2 | Release saga, Tekton CloudEvents, retire legacy `update-gitops` | Events 2 → 4, GitOps 4 → 5, orchestration 4 → 5 |
| Phase 3 | `CiPort` with GitHub Actions | FinOps 4 → 5 |
| Phase 4 | Signing, SBOM and provenance in the trust policy; OpenTelemetry and SLO alerts; metric analysis | Supply chain 2 → 4, observability 3 → 4, verification 3 → 5 |
| Phase 5 | GitHub App, Key Vault secrets, Central India, restore drill | Identity 3 → 5, backup 3 → 4 |

## Added in this round (Harness / Argo Rollouts parity)

- **Failure strategy per environment** (`AIO_PROMOTION_MANUAL_ENVS`). A failed deployment pauses for an approver, who can retry, skip or roll back. Nobody deciding within `AIO_PROMOTION_INTERVENTION_TIMEOUT` (2 h) rolls it back. A paused deployment does not keep the platform awake.
- **Smoke-test verification** after Healthy. It samples `GET <public URL><AIO_PROMOTION_SMOKE_PATH>` until 4 answers come back without a 5xx; 2 bad samples fail the deployment. It is skipped when the environment has no public URL.
- **Deployment freeze windows** (`AIO_FREEZE_WINDOWS`), weekly or absolute. They block promotions, prod sync and prod values edits. An AIO admin can override a freeze on one approval; the override is audited.
- **Execution timeline**: `GET /api/promotions/{id}/execution`, plus decision buttons and a freeze banner on the Approvals page.

Behaviour change to know: the smoke test is **on by default**. If an app's `/` returns a 5xx when the app is healthy, set `AIO_PROMOTION_SMOKE_PATH` to a health path or `AIO_PROMOTION_SMOKE=false` before deploying.
