"""Score the AIO assistant's answers with Ragas (faithfulness: is every claim supported by the retrieved context?).

Input: platform/backend/target/assistant-eval.jsonl written by AssistantLiveEval.
Judge: any OpenAI-compatible endpoint (a free tier works); set JUDGE_BASE_URL, JUDGE_MODEL, JUDGE_API_KEY.
Gate: exits 1 when mean faithfulness < FAITHFULNESS_MIN (default 0.8) or any grounded answer scores < 0.5.

Not yet run against a live model: pin the versions in requirements.txt and check the Ragas API if it has moved.
"""
import json
import os
import statistics
import sys

from langchain_openai import ChatOpenAI
from ragas import EvaluationDataset, RunConfig, evaluate
from ragas.llms import LangchainLLMWrapper
from ragas.metrics import Faithfulness


def main(path: str) -> int:
    rows = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    grounded = [r for r in rows if r.get("grounded")]
    print(f"{len(rows)} answers, {len(grounded)} grounded with citations")
    if not grounded:
        print("no grounded answers to score")
        return 1

    dataset = EvaluationDataset.from_list([
        {"user_input": r["user_input"], "response": r["response"], "retrieved_contexts": r["retrieved_contexts"]}
        for r in grounded
    ])
    judge = LangchainLLMWrapper(ChatOpenAI(
        base_url=os.environ["JUDGE_BASE_URL"],
        api_key=os.environ.get("JUDGE_API_KEY", "none"),
        model=os.environ["JUDGE_MODEL"],
        temperature=0,
    ))
    # free tiers allow few requests per minute: judge one call at a time, back off on 429
    run_config = RunConfig(max_workers=int(os.environ.get("JUDGE_MAX_WORKERS", "1")), max_retries=6, max_wait=90,
                           timeout=180)
    result = evaluate(dataset=dataset, metrics=[Faithfulness(llm=judge)], run_config=run_config)
    df = result.to_pandas()
    # per-answer scores (questions and scores only) so a low score can be traced to its question
    out = os.path.join(os.path.dirname(os.path.abspath(path)), "assistant-ragas-scores.csv")
    df[["user_input", "faithfulness"]].to_csv(out, index=False)
    for q, f in sorted(zip(df["user_input"], df["faithfulness"]), key=lambda x: (x[1] == x[1], x[1]))[:5]:
        print(f"  low: {f:.2f}  {q[:90]}")
    print(f"per-answer scores: {out}")
    scores = [s for s in df["faithfulness"].tolist() if s == s]  # drop NaN
    mean = statistics.mean(scores) if scores else 0.0
    worst = min(scores) if scores else 0.0
    minimum = float(os.environ.get("FAITHFULNESS_MIN", "0.8"))
    print(f"faithfulness mean={mean:.2f} worst={worst:.2f} (gate {minimum})")
    return 0 if mean >= minimum and worst >= 0.5 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "platform/backend/target/assistant-eval.jsonl"))
