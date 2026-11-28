# GitHub webhook buffer (Azure Function)

AIO sleeps when idle (docs/AZURE-COST-HIBERNATION-2026-11-15.md). Before this buffer, a push while AIO slept got a
`503` from Traefik and its build was lost. Now an Azure Function in front of the webhook path catches every
delivery, wakes AIO and replays it.

```
GitHub ─► https://app.sarvaikam.com/api/webhooks/github/{id}      (GitHub settings unchanged)
        ─► Traefik IngressRoute aio-webhooks-to-buffer (priority 100, ExternalName service)
        ─► Function aio-webhook-buffer (Flex Consumption, South India, Python 3.13, 512 MB, max 1 instance)

  AIO awake  ─► forward at once to /buffered/api/webhooks/github/{id} ─► AIO; GitHub gets AIO's own reply
  AIO asleep ─► save delivery to Blob webhook-inbox/{id}/{delivery}.json + queue github-webhooks
               wake AIO (GET on the gated route), answer 202 "buffered"
               queue worker: every 30 s, if AIO health is "UP" replay byte-for-byte, else wake again and retry
```

| Piece | Where |
|---|---|
| Function code | `ops/azure/webhook-buffer/` (`function_app.py`, `host.json`, `requirements.txt`) |
| Routing | `ops/portable/azure-webhook-buffer.yaml`, ungated `/buffered/...` paths in `azure-native-routes.yaml` |
| Traefik flag | `providers.kubernetesCRD.allowExternalNameServices: true` in `azure-ingress-values.yaml` |
| Azure resources | RG `creative`: function app `aio-webhook-buffer`, storage account `aiowebhookbuffer` (LRS), and the plan the CLI created for it |

**Security:** the function holds no secrets. AIO still verifies every delivery's HMAC signature
(`X-Hub-Signature-256`), so a forged request only gets a 401 from AIO.
- The function itself rejects unsigned requests (`400`) and bodies over 10 MB (`413`).
- `/buffered/` exposes only the webhook and health endpoints, both of which were already public.

**Retries:** `maxDequeueCount 40` × `visibilityTimeout 30 s` gives about 20 min. After that the message moves to
`github-webhooks-poison` and the body stays in `webhook-inbox` for inspection.
- 2xx and 4xx replies from AIO end the retry, because a 4xx (bad signature, unknown project) will never succeed.
- 5xx, 429 and timeouts retry.

**Duplicates:** a delivery can reach AIO twice, for example when AIO took longer than the 5 s direct-forward timeout
but still started the build, and the buffer then replayed it. AIO de-duplicates by `X-GitHub-Delivery` per project
(table `webhook_deliveries`, `WebhookInbox`), after the signature check:
- already handled: AIO answers with the first reply (header `X-AIO-Webhook-Replay: true`) and starts nothing;
- first attempt still running: `429` with `Retry-After: 30`, so the buffer retries; after 2 min without a reply
  (`app.webhooks.stale-after`) the next attempt takes it over;
- same id, different body: `409`, never processed;
- first attempt failed (e.g. `400`): the id is released, so a redelivery from GitHub is processed again.

A **Redeliver** from GitHub's webhook settings re-sends the same id, so it no longer starts a second build once the
first succeeded; use **Rerun** in AIO instead. Entries are kept 14 days (`app.webhooks.inbox-retention`).

**Ordering:** `maximumInstanceCount 1` + `batchSize 1` replays one delivery at a time.

**Cost:** Flex Consumption free grant (100 000 GB-s and 250 000 executions per month) plus a few cents of storage.
Application Insights is not attached (no log ingestion cost), so check the inbox and queues instead:

```bash
az storage blob list    --account-name aiowebhookbuffer -c webhook-inbox --auth-mode key -o table   # pending
az storage message peek --account-name aiowebhookbuffer -q github-webhooks --auth-mode key            # retrying
az storage queue exists --account-name aiowebhookbuffer -n github-webhooks-poison --auth-mode key     # given up
```

**Deploy code changes:**

```bash
cd ops/azure/webhook-buffer && zip ../webhook-buffer.zip function_app.py host.json requirements.txt
az functionapp deployment source config-zip -g creative -n aio-webhook-buffer --src ../webhook-buffer.zip --build-remote true
```

## Tested 2026-11-15

The test used a fake delivery with an invalid signature, so no secret was needed. AIO answering `401 bad signature`
proves the whole path.

| Case | Result |
|---|---|
| AIO awake | GitHub → Traefik → function → AIO in 0.5–0.8 s; AIO's `401` passed straight back |
| Unsigned request | function's own `400` (proves the function is in the path) |
| AIO asleep | `202 buffered` in 2 s; AIO ready after 70 s; delivery replayed and cleared after **97 s**; nothing in poison |

## Rollback

`kubectl delete ingressroute -n aio-platform aio-webhooks-to-buffer`. `/api/webhooks` then falls back to the
ungated Ingress, which goes straight to AIO and returns 503 while AIO sleeps.

## Still open

A build can stall if every AIO tab is closed and the 30 min session expires mid-build. The fix is for AIO to renew
its own Sablier session while a run is active (small AIO change).
