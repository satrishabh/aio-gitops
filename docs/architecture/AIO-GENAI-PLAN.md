# AIO GenAI and event-driven plan

16 Nov 2026. Goal: an assistant ("Ask AIO") that helps across the whole delivery process, up to taking a new
application idea to a shipped project, built **one use case at a time, safety first, at near-zero running cost**.
Kafka-class messaging is the later horizon, adopted only when a use case needs it.
Product view (who it is for, value, measures, rollout): `docs/product/AIO-ASSISTANT-PRODUCT.md`.

## 1. Principles

1. **One use case per slice.** Each slice is off by default behind its own flag, has an eval gate, and can be turned off without a deploy.
2. **Read before write.** The assistant first explains, then drafts, and only at the end acts. It acts only through existing AIO APIs, inside a saga, after a person approves.
3. **Least data out.** Only curated, redacted text leaves the cluster. Code, logs and secrets go out only in later slices that explicitly need them, after redaction, and per project with consent.
4. **Free first, swappable always.** The `LlmClient` port uses the OpenAI-compatible protocol, so Groq, OpenRouter, Cerebras, Mistral or a local Ollama are a configuration change. A Claude adapter on the official Anthropic SDK is the quality option when a paid tier is justified.
5. **Measured.** Retrieval evals run in CI; answer-quality evals (Ragas faithfulness) gate model or prompt changes.

## 2. Safety model (OWASP Top 10 for LLM applications)

| Risk | Control in AIO |
|---|---|
| LLM01 prompt injection (direct and via retrieved text) | Retrieved text is wrapped in source tags and declared data in the system prompt; no tools in slices G1–G3, so an injection can change only words, not systems; actions in G4+ need a human click and run as sagas |
| LLM02 sensitive information disclosure | `Redactor` on the question, the history, the retrieved context and the answer; curated knowledge folder is an allowlist with a CI test that it holds nothing redactable; audit records sizes, never text |
| LLM06 excessive agency | Read-only until G4; then a fixed list of tools that call existing AIO APIs with the user's own permissions, prod changes still go through approvals and freeze windows |
| LLM09 misinformation | Grounded or silent: no sources means a fixed answer without calling the model; numbered citations; UI says "check the source before acting" |
| LLM10 unbounded consumption | Question and history caps, output token cap, per-person hourly and global daily limits sized to free tiers |
| Supply chain of models and data | Provider is configuration; free tiers may log prompts, so only curated docs are sent; anything else needs a provider with a no-training agreement or a self-hosted model |

## 3. Use cases, one by one

| Slice | Use case | What the model sees | Can it act? | Status |
|---|---|---|---|---|
| **G1** | **Ask AIO**: how-to and policy questions with cited answers | Curated docs (redacted) | No | **Built on branch** (171 tests, retrieval recall@5 = 1.00) |
| **G2** | **Explain a failure**: why did this CI run or deployment fail, and what to try | Status, failed steps, exit codes and saga events of a project the user can see; the last 60 log lines only with `AIO_ASSISTANT_SHARE_LOGS=true`, redacted | No | **Built on branch** |
| G3 | **Idea to spec**: describe an app, get a spec, a template choice, a Dockerfile and values draft | The idea, the template catalogue | No: draft only; the person clicks New project | Planned |
| G4 | **Do it for me, with approval**: create project, promote, open a PR with the draft | As G3, plus tool results | Yes, through existing APIs as sagas, every step confirmed by a person; prod via approvals | Planned |
| G5 | **Approval co-pilot**: risk summary for a prod request (diff of images, scan delta, recent failures) | Scan and deployment data for that project | No | Planned |
| G6 | **Ops watch**: summarise overnight failures and cost each morning | Saga events, metrics | No | Horizon |

## 4. Open-source building blocks (chosen for a single small node)

| Need | Now (zero extra infra) | Next | Later, only if needed |
|---|---|---|---|
| Models | Free OpenAI-compatible tiers (Groq, OpenRouter, Cerebras, Mistral) | Claude via official SDK for quality-critical slices | Self-hosted Ollama / vLLM on a GPU node |
| Retrieval | In-memory BM25 over curated Markdown | pgvector + full-text hybrid search with reciprocal rank fusion in the existing PostgreSQL | Dedicated vector DB only past ~1M chunks |
| Evals | JUnit retrieval eval (CI) + Ragas faithfulness (offline) | DeepEval or Ragas in CI on a nightly schedule; promptfoo red-team prompts | Langfuse datasets from production traces |
| Guardrails | Redaction, tagged sources, no tools, caps | LLM Guard or Llama Guard classifier on input and output for G4 | NeMo Guardrails dialogue rails |
| Observability | Audit log (sizes, sources, tokens) | Langfuse (self-hosted, MIT) traces with redacted payloads | OpenTelemetry GenAI semantic conventions to the platform tracing |
| Workflow for agents | AIO saga engine (approvals, compensation, timeouts) | Same | Temporal only if sagas outgrow one database |

## 5. Events and queues: the horizon

Kafka is not needed at today's scale, and it would cost more than the rest of the platform. Adopt in steps:

1. **Transactional outbox in PostgreSQL** (`outbox` table written in the same transaction, delivered by the saga worker). Gives reliable events with no new infrastructure.
2. **PostgreSQL queue** (`SELECT … FOR UPDATE SKIP LOCKED`, or pgmq) for background jobs such as embeddings and nightly evals.
3. **NATS JetStream** (one small Go binary, persistence and replay) when several services need fan-out or request-reply.
4. **Kafka API (Redpanda or Apache Kafka KRaft)** only for high-volume streams or a Kafka-only integration; the outbox then relays into it unchanged.

## 6. Deploying G1

1. Pick a provider, create a key, store it as a Kubernetes Secret (never in values files or chat).
2. Set `AIO_ASSISTANT_ENABLED=true`, `AIO_ASSISTANT_BASE_URL`, `AIO_ASSISTANT_MODEL`, `AIO_ASSISTANT_API_KEY` (from the Secret).
3. Run layer 3 of `evals/assistant/README.md` once with that model; ship only if faithfulness ≥ 0.8.
4. Watch the audit log for a week; grow the golden set from real questions.
