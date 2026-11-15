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
from ragas import EvaluationDataset, evaluate
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
    result = evaluate(dataset=dataset, metrics=[Faithfulness(llm=judge)])
    scores = [s for s in result.to_pandas()["faithfulness"].tolist() if s == s]  # drop NaN
    mean = statistics.mean(scores) if scores else 0.0
    worst = min(scores) if scores else 0.0
    minimum = float(os.environ.get("FAITHFULNESS_MIN", "0.8"))
    print(f"faithfulness mean={mean:.2f} worst={worst:.2f} (gate {minimum})")
    return 0 if mean >= minimum and worst >= 0.5 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "platform/backend/target/assistant-eval.jsonl"))
