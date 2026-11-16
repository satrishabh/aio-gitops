# Evaluating the "Ask AIO" assistant

Three layers. The first runs on every build; the other two run when you choose to spend free-tier quota.

| Layer | What it checks | How | Gate |
|---|---|---|---|
| 1. Retrieval (CI) | The right document and section are retrieved for 15 golden questions | `mvn test -Dtest=AssistantTest` (no model, no key) | recall@5 ≥ 0.9, MRR ≥ 0.7 (today 1.00 / 0.89) |
| 2. Safety (CI) | Redaction in and out, sources wrapped as data, no answer without sources, rate limits, history caps | same test class | all pass |
| 3. Answer quality (offline) | Faithfulness: every claim in the answer is supported by the retrieved context | `AssistantLiveEval` exports answers, then Ragas scores them | mean ≥ 0.8, worst ≥ 0.5 |

## Run layer 3

```bash
# 1. answers from the assistant, with the model you would deploy (Google Gemini free tier)
#    read the key from your secret store into the variable; never type it into chat or a file in the repo
export AIO_EVAL_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai AIO_EVAL_MODEL=gemini-2.5-flash
export AIO_EVAL_REASONING_EFFORT=none AIO_EVAL_PAUSE_SECONDS=7   # stay under the free per-minute limit
export AIO_EVAL_API_KEY=<key>
cd platform/backend && mvn test -Dtest=AssistantLiveEval && cd ../..

# 2. score them (a different model as judge is better than self-judging)
python -m venv .venv-evals && . .venv-evals/bin/activate
pip install -r evals/assistant/requirements.txt
# Gemini works as the judge too (same endpoint and key); a model from another provider avoids self-judging bias
export JUDGE_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai JUDGE_MODEL=gemini-2.5-flash JUDGE_API_KEY=$AIO_EVAL_API_KEY
python evals/assistant/ragas_eval.py platform/backend/target/assistant-eval.jsonl
```

Never commit keys or the exported JSONL; `target/` is ignored by git.

## Growing the golden set

Add a line to `platform/backend/src/test/resources/assistant-golden.json` for every question people actually ask that
went wrong, with the document and heading that should answer it. Fix the knowledge (or retrieval) until layer 1 passes
again. This is the regression suite for the assistant.

## Results so far

**16 Nov 2026, local model `qwen2.5:1.5b` on Ollama (laptop CPU), 20 golden questions + 1 failure explanation:**
- The whole pipeline worked end to end: retrieval, redaction, prompt, answer, sources.
- Answers were on topic and mostly faithful to the docs (manual review); one mixed up two approval rules.
- **0 of 21 answers used the `[n]` citation format**: a 1.5B model ignores that instruction, so the UI shows
  sources as "related, not cited". Use a larger model (free-tier 70B-class) and confirm citations with layer 3.
- About 73 s per answer on CPU: a laptop-sized local model is too slow for daily use; use a free cloud tier, or a
  GPU node if data must stay in-house.
- Found and fixed: a provider timeout surfaced as a raw I/O error; it now says the provider did not answer in time.
- The model, Ollama and Docker were stopped and the model deleted afterwards (nothing left running).

Run your own live eval with a local model:
`AIO_EVAL_BASE_URL=http://localhost:11434/v1 AIO_EVAL_MODEL=<model> AIO_EVAL_TIMEOUT_SECONDS=240 mvn test -Dtest=AssistantLiveEval`

The live eval also writes one G2 explanation and one G6 daily summary (from synthetic facts), so a single run
checks every way the assistant calls the model. About 23 requests: under 10% of a free day.

## Not yet done

- Ragas faithfulness (layer 3 scoring) has not been run: it needs a judge model key.
- Red-team prompts (promptfoo) and production tracing (Langfuse) are planned in `docs/architecture/AIO-GENAI-PLAN.md`.
