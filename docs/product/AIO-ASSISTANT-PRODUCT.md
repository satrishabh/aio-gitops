# AIO Assistant: product brief

16 Nov 2026 · Status: slices G1 (Ask AIO) and G2 (Explain a failure) built on branch `feat/aio-v2-saga-core`, off by
default, not deployed. Technical plan: `docs/architecture/AIO-GENAI-PLAN.md`.

## The problem

AIO already automates building, deploying, approving and rolling back. What still costs people time is
**understanding**: why a build failed, what "Needs decision" means, why AIO is slow at 9 am, what to do when a prod
deployment pauses. Today that knowledge lives in long runbooks and in one or two people's heads. Every question goes to
them, and every failed build starts with reading logs from the bottom up.

## Who it is for

| Person | Today | With the assistant |
|---|---|---|
| App developer | Reads raw build logs, asks in chat what a status means | Clicks **Explain with AIO** on a failed run: likely cause, next steps, cited |
| Approver | Opens several pages to judge a prod request | (G5) A short risk summary on the request: image change, scan delta, recent failures |
| Platform owner | Answers the same questions, writes runbooks nobody finds | Runbooks become the assistant's knowledge; repeated questions become golden tests |
| New team or founder | Has an app idea, does not know the platform | (G3) Describes the idea; gets a spec, a template choice and a draft to review |

## What ships, slice by slice

| Slice | User-visible feature | Value | Risk kept low by |
|---|---|---|---|
| **G1 Ask AIO** (built) | A page that answers how-to and policy questions with numbered sources | Fewer interruptions for the platform owner; answers at 2 am | Only curated docs; "not in the docs" instead of guessing |
| **G2 Explain a failure** (built) | **Explain with AIO** on failed CI runs and on failed or paused deployments | Shorter time from failure to the right fix; less log reading | Metadata only by default; logs need an admin opt-in and are redacted; it never changes anything |
| G3 Idea to spec | Describe an app, get a spec, template choice, Dockerfile and values draft | New applications start in minutes, on the paved road | Draft only; the person clicks **New project** |
| G4 Do it, with approval | The assistant prepares the project or promotion; a person confirms each step | Fewer clicks for routine work | Runs as sagas through existing APIs, with approvals and freeze windows |
| G5 Approval co-pilot | Risk summary on each prod request | Faster, better-informed approvals | Advisory text only |
| G6 Morning summary | What failed overnight and what it cost | Problems seen before users report them | Read-only, scheduled |

## How we will know it works

| Measure | Source | Target after 4 weeks of use |
|---|---|---|
| Answer faithfulness (claims supported by sources) | Ragas, before every model or prompt change | ≥ 0.8 mean |
| Retrieval recall@5 on the golden set | CI on every build (today 1.00 on 20 questions) | ≥ 0.9, set growing every week |
| Questions answered without a person | Audit log (`assistant.ask`, grounded vs not found) | Most how-to questions grounded |
| Time from failed run to next successful run | CI run history, before vs after G2 | Down, tracked weekly |
| Change failure rate and time to restore (DORA) | Metrics page | No worse; ideally better after G2 and G5 |
| Cost | Provider usage, free-tier caps | US$0 on free tiers; daily cap never hit in normal use |

## Cost

- **Free tier first.** Google Gemini 2.5 Flash on the free tier is the chosen model; Groq, OpenRouter, Cerebras or Mistral are drop-in fallbacks. Free tiers cover a small team's daily questions. Caps per person and per day stop runaway use.
- **No new infrastructure.** Retrieval runs in memory inside AIO, so there is no vector database and no extra pods.
- **Local option.** Ollama on a developer laptop or a larger node keeps all data in-house, at the cost of answer quality and memory.
- **Paid when it pays.** For G3 and G4, where wrong answers cost more, Claude via the official Anthropic SDK is the planned option. It is per-token, so it stays behind the same caps.

## Safety promises to users

1. It cannot change anything, until G4 adds explicit, confirmed actions.
2. It shows where each answer came from, and says when it does not know.
3. Secrets are masked before anything leaves AIO and again in the answer; the audit log never stores the question.
4. Admins turn it on or off with one setting, and choose whether build logs may be shared.

## Rollout

1. Enable on the platform team only for two weeks. Every wrong answer becomes a golden test and a knowledge fix.
2. Enable for all developers with G1 and G2. Logs stay off until the redaction has been reviewed on real failures.
3. Measure the table above monthly; start G3 only when G1 and G2 hold their targets.

## Open questions for the owner

- ~~Which provider and model?~~ Decided 16 Nov 2026: Google Gemini 2.5 Flash, free tier. Free-tier prompts may be used by Google, so only curated docs and run metadata are sent; revisit if the free tier is cut.
- May build logs be shared with that provider (`AIO_ASSISTANT_SHARE_LOGS`)?
- Who owns the knowledge folder and the golden set (a weekly 15-minute review)?
