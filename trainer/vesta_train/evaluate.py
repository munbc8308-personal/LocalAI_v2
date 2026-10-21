"""평가 게이트 (PROJECT.md 7.10 L-06): 시험셋 정답률(D1·D2·D3)과 판정 재연 비교용 항목에서 지금 모델과 같은 답을 내는 비율"""
import argparse
import json
from collections import defaultdict

from .data import replay_examples, routing_examples
from .model import Winnow


def score(w: Winnow, examples) -> dict:
    right, total, wrong = defaultdict(int), defaultdict(int), []
    for e in examples:
        probs = w.probabilities(e.prompt, e.count)
        pick = max(range(e.count), key=probs.__getitem__)
        total[e.kind] += 1
        if pick == e.answer:
            right[e.kind] += 1
        else:
            wrong.append({"kind": e.kind, "source": e.source, "want": e.answer, "got": pick})
    return {"accuracy": {k: round(right[k] / total[k], 4) for k in total}, "counts": dict(total), "wrong": wrong}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True)
    p.add_argument("--adapter")
    p.add_argument("--test", nargs="+", required=True)
    p.add_argument("--replay")
    p.add_argument("--ollaya", default="http://127.0.0.1:11435")
    args = p.parse_args()
    w = Winnow(args.base, args.adapter)
    report = {"adapter": args.adapter, "tests": {}}
    for path in args.test:
        report["tests"][path] = score(w, routing_examples(path))
    if args.replay:
        _, holdout = replay_examples(args.replay, url=args.ollaya)
        report["replay"] = score(w, holdout)
    print(json.dumps({"event": "report", **report}, ensure_ascii=False))


if __name__ == "__main__":
    main()
