"""임베딩 평가 게이트 (PROJECT.md L-23): 지금 모델과 새 모델을 같은 조각 전체(corpus)에 대해 벡터 검색만으로 비교한다 (Ollama /api/embed).
- 떼어 둔 재료(heldout): 질문의 정답 조각 순위 → hit@5·MRR
- 평가셋(eval): 사람이 쓴 질문, 정답 위치(파일 경로·절 제목) 글자가 든 조각 → hit@5·MRR
통과 조건: 떼어 둔 재료 MRR이 오르고, 평가셋 hit@5가 줄지 않고 MRR이 0.01 넘게 떨어지지 않으며, 떼어 둔 재료가 [--min-heldout]개 이상
"""
import argparse
import json
import urllib.request

import numpy as np


def embed(url: str, model: str, texts: list[str], batch: int = 32) -> np.ndarray:
    out = []
    for i in range(0, len(texts), batch):
        body = json.dumps({"model": model, "input": texts[i:i + batch], "truncate": True}).encode()
        out += json.load(urllib.request.urlopen(urllib.request.Request(f"{url}/api/embed", body, {"Content-Type": "application/json"}), timeout=600))["embeddings"]
    v = np.array(out, dtype=np.float32)
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def ranks(url: str, model: str, corpus: list[dict], docs: np.ndarray, queries: list[str], hit) -> list[int | None]:
    q = embed(url, model, queries)
    order = np.argsort(-(q @ docs.T), axis=1)[:, :50]
    out = []
    for i, row in enumerate(order):
        rank = next((r + 1 for r, j in enumerate(row) if hit(i, corpus[j])), None)
        out.append(rank)
    return out


def metrics(r: list[int | None]) -> dict:
    n = max(len(r), 1)
    return {"hit5": sum(1 for x in r if x and x <= 5), "mrr": round(sum(1 / x for x in r if x and x <= 10) / n, 4), "total": len(r)}


def score(url: str, model: str, corpus: list[dict], heldout: list[dict], evals: list[dict]) -> dict:
    docs = embed(url, model, [c["text"] for c in corpus])
    held = ranks(url, model, corpus, docs, [h["query"] for h in heldout], lambda i, c: c["id"] == heldout[i]["id"])
    ev = ranks(url, model, corpus, docs, [e["query"] for e in evals], lambda i, c: any(x in c["ref"] for x in evals[i]["expected"]))
    return {"heldout": metrics(held), "eval": metrics(ev), "evalRanks": ev}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True)
    p.add_argument("--candidate", required=True)
    p.add_argument("--corpus", required=True)
    p.add_argument("--heldout", required=True)
    p.add_argument("--eval", required=True)
    p.add_argument("--min-heldout", type=int, default=10)
    p.add_argument("--ollama", default="http://127.0.0.1:11434")
    args = p.parse_args()
    load = lambda path: [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    corpus, heldout, evals = load(args.corpus), load(args.heldout), load(args.eval)
    base = score(args.ollama, args.base, corpus, heldout, evals)
    cand = score(args.ollama, args.candidate, corpus, heldout, evals)
    reasons = []
    if len(heldout) < args.min_heldout:
        reasons.append(f"떼어 둔 재료가 {len(heldout)}개뿐 (최소 {args.min_heldout})")
    if cand["heldout"]["mrr"] <= base["heldout"]["mrr"]:
        reasons.append("떼어 둔 재료 MRR이 오르지 않음")
    if cand["eval"]["hit5"] < base["eval"]["hit5"]:
        reasons.append("평가셋 hit@5가 줄어듦")
    if cand["eval"]["mrr"] < base["eval"]["mrr"] - 0.01:
        reasons.append("평가셋 MRR이 떨어짐")
    worse = [{"query": e["query"], "base": b, "candidate": c} for e, b, c in zip(evals, base["evalRanks"], cand["evalRanks"]) if (c or 99) > (b or 99)]
    print(json.dumps({
        "event": "gate", "passed": not reasons, "reasons": reasons, "corpus": len(corpus),
        "base": {"model": args.base, "heldout": base["heldout"], "eval": base["eval"]},
        "candidate": {"model": args.candidate, "heldout": cand["heldout"], "eval": cand["eval"]},
        "worse": worse,
    }, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
