# AIO v2 – Phase 1 (branch `feat/aio-v2-saga-core`)

Built overnight on 16 Nov 2026 as planned in [AIO-V2-ARCHITECTURE.md](AIO-V2-ARCHITECTURE.md) §7.
**Nothing is deployed.** Live AKS and `main` were not touched.
Open the PR: https://github.com/satrishabh/aio-gitops/compare/main...feat/aio-v2-saga-core?expand=1
(the description below can be pasted into it).

---

## PR description

### Summary
First phase of the AIO v2 redesign. It adds durable sagas, idempotent webhook intake, and scale-to-zero friendly
behaviour, and fixes four reliability bugs found in the architecture review.

| Area | Change |
|---|---|
| **Saga engine** (new `com.aio.platform.saga`) | PostgreSQL-backed orchestrator. Each `advance` runs one step: claim with a lease and optimistic lock, execute **outside** any transaction, persist the outcome and history. Results are `Done`, `Wait` (re-check later), `Retry` (exponential backoff to 5 min) and `Fail`. Overall deadline; reverse-order compensation; `FAILED` when an undo itself fails, flagged for a human. The worker sleeps until the next due saga, so there is no fixed-rate polling. |
| **Promotions / rollbacks** | Now the `promotion` saga: capture current tag → commit tag (↺ restore previous tag, re-sync prod) → sync → **verify Synced + Healthy + running the new image**. Degraded > 5 min or not healthy within 20 min → **automatic revert**. New status `APPLYING`. |
| **Approval race** | Approve is an atomic `PENDING → APPLYING` claim, plus `@Version`. Two approvers or a double click can no longer both apply. A concurrent edit returns 409. |
| **Webhooks** | Inbox keyed by `X-GitHub-Delivery`: redeliveries are acknowledged without work. A push or PR for a commit already pending, running or succeeded is not built again. A failed processing attempt is forgotten so the retry gets through. |
| **Keep-awake lease** | While a saga, CI run or scan is active, AIO renews its Sablier session (`/api/strategies/poke`) every 5 min. Builds no longer stall when the tab is closed. Idle means no calls. |
| **UI polling** | Paused while the tab is hidden or after 15 min with no input; resumes on return. A forgotten tab no longer keeps AIO, Argo CD and Tekton awake. |
| **Stuck runs** | LEGACY runs without a saved PipelineRun name are reattached by label, or failed after 10 min (they used to stay PENDING forever). |
| **Scheduler** | `@Scheduled` pool 1 → 4 threads. |

Schema: `V10__sagas.sql` (saga tables + promotions version/saga_id/previous_tag) and `V11__webhook_inbox.sql`.

### Tests
- Backend: **136/136** pass (`mvn test`). New tests:
  - `SagaEngineTest` (13)
  - `PromotionSagaTest` (7)
  - `WebhookIdempotencyTest` (6)
  - `KeepAwakeLeaseTest` (6, against a fake Sablier HTTP server)
  - `StuckRunRecoveryTest` (3)
- Frontend: `node --test platform/frontend/tests/activity.test.mjs` (5) passes, and `vite build` passes.
- **Not yet done:** running the Flyway migrations V10/V11 against real PostgreSQL. Tests use H2 with Hibernate-generated schema. The first deploy runs them, so do the deploy in a quiet window (see below).

### Behaviour changes to know
1. **The promote button no longer returns DONE immediately.** The request shows `APPLYING` until Argo CD reports the new image healthy (typically 1–3 min). DONE is now a stronger promise.
2. **A prod promotion whose rollout goes Degraded is reverted automatically.** To turn health verification off: `AIO_PROMOTION_VERIFY_HEALTH=false`.
3. **The saga worker runs even with `AIO_WORKERS_ENABLED=false`,** because promotions need it. Turn it off with `AIO_SAGA_WORKERS_ENABLED=false`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

---

## Deploy runbook (when you decide to ship it)

1. **Review and merge the PR.** Let CI build the AIO image, or build it the usual way, and note the image digest.
2. **Back up the database first.** The nightly backup exists, but take a fresh one:
   ```bash
   kubectl -n aio-validation create job --from=cronjob/postgres-backup pre-v2-$(date +%s)
   ```
3. **Apply the Sablier NetworkPolicy change** (lets AIO poke Sablier):
   ```bash
   kubectl apply -f ops/portable/azure-hibernation.yaml
   ```
4. **Upgrade AIO** with `ops/portable/azure-native-aio-values.yaml` (new digest).
   - The values file now carries `AIO_SAGA_WORKERS_ENABLED`, `AIO_KEEP_AWAKE_SABLIER_URL`, and `AIO_CI_WORKERS_ENABLED=true`, which was previously set live by hand.
   - Flyway applies V10 and V11 on start.
5. **Verify:**
   - `kubectl -n aio-platform logs deploy/aio-platform | grep -i flyway` shows V11 applied.
   - Promote dev → staging on a test project. It goes `APPLYING`, then `DONE`, and the Approvals page shows it.
   - Redeliver a webhook from GitHub's settings page; the answer is `duplicate delivery …` and no second build starts.
   - While a build runs and no tab is open, after 30+ min `kubectl get deploy -n aio-platform` still shows 1/1.
6. **Rollback:** redeploy the previous AIO digest.
   - V10 and V11 only add tables and nullable or defaulted columns, so the old code runs against the new schema.
   - The NetworkPolicy change is additive.

## Next (Phase 2)
- **Release saga:** build → gate → commit digest → verify healthy. It replaces `PipelineWatcher` and `BuildReleaseWorker` polling, with Tekton CloudEvents as the trigger.
- **Enable on Azure** and retire the legacy `update-gitops` git push, so there is one writer per values file.
- **`CiPort` with a GitHub Actions adapter:** removes Tekton, per-run disks and the build-time second node.
