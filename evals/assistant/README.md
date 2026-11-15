# Evaluating the "Ask AIO" assistant

Three layers. The first runs on every build; the other two run when you choose to spend free-tier quota.

| Layer | What it checks | How | Gate |
|---|---|---|---|
| 1. Retrieval (CI) | The right document and section are retrieved for 15 golden questions | `mvn test -Dtest=AssistantTest` (no model, no key) | recall@5 ≥ 0.9, MRR ≥ 0.7 (today 1.00 / 0.89) |
| 2. Safety (CI) | Redaction in and out, sources wrapped as data, no answer without sources, rate limits, history caps | same test class | all pass |
| 3. Answer quality (offline) | Faithfulness: every claim in the answer is supported by the retrieved context | `AssistantLiveEval` exports answers, then Ragas scores them | mean ≥ 0.8, worst ≥ 0.5 |

## Run layer 3

```bash
# 1. answers from the assistant (uses the model you would deploy)
export AIO_EVAL_BASE_URL=https://api.groq.com/openai/v1 AIO_EVAL_MODEL=<model id> AIO_EVAL_API_KEY=<key from your secret store>
cd platform/backend && mvn test -Dtest=AssistantLiveEval && cd ../..

# 2. score them (a different model as judge is better than self-judging)
python -m venv .venv-evals && . .venv-evals/bin/activate
pip install -r evals/assistant/requirements.txt
export JUDGE_BASE_URL=https://openrouter.ai/api/v1 JUDGE_MODEL=<judge model id> JUDGE_API_KEY=<key>
python evals/assistant/ragas_eval.py platform/backend/target/assistant-eval.jsonl
```

Never commit keys or the exported JSONL; `target/` is ignored by git.

## Growing the golden set

Add a line to `platform/backend/src/test/resources/assistant-golden.json` for every question people actually ask that
went wrong, with the document and heading that should answer it. Fix the knowledge (or retrieval) until layer 1 passes
again. This is the regression suite for the assistant.

## Not yet done

- Layer 3 has not been run against a live model (no key was used while building it).
- Red-team prompts (promptfoo) and production tracing (Langfuse) are planned in `docs/architecture/AIO-GENAI-PLAN.md`.
