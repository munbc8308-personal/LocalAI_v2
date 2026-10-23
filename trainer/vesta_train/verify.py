"""평가 게이트 (PROJECT.md 7.10 L-06, L-22): 실제로 쓰일 Ollaya 모델 두 개(지금 것·새 것)를 같은 시험셋으로 비교한다.
- 라우팅 시험셋 D1·D2·D3 정답률
- 판정 시험셋(정답이 붙은 D6·D7·D10) 정답률
- 판정 재연 항목에서 지금 모델과 같은 답을 내는 비율 (정답 시험셋이 없는 D4·D8·D11이 망가지지 않았는지)
통과 조건: 전체 정답 수가 늘고, 질문(D1·D2·D3·D6·D7·D10)마다 정답 수가 줄지 않고, 재연 일치율이 기준 이상.
"""
import argparse
import json
import urllib.request

from .data import judge_examples, judge_question, routing_rows
from .winnow_prompt import QUESTIONS, options


def decide(url: str, model: str, state: str, questions: dict) -> dict:
    body = {"model": model, "state": state, "questions": questions}
    return json.load(urllib.request.urlopen(urllib.request.Request(f"{url}/api/decide", json.dumps(body).encode(), {"Content-Type": "application/json"}), timeout=120))["answers"]


def pick(answer: dict) -> str:
    return answer["choice"] if answer["type"] == "choice" else ("true" if answer["noul"] >= 0.5 else "false")


def routing_score(url: str, model: str, paths: list[str]) -> dict:
    right = {"agent": 0, "needsWeb": 0, "needsLocal": 0}
    total, wrong = 0, []
    for path in paths:
        for question, agent, web, local in routing_rows(path):
            total += 1
            a = decide(url, model, question, QUESTIONS)
            want = {"agent": agent, "needsWeb": "true" if web == "1" else "false", "needsLocal": "true" if local == "1" else "false"}
            for k in right:
                got = pick(a[k])
                if got == want[k]:
                    right[k] += 1
                else:
                    wrong.append({"q": k, "text": question, "want": want[k], "got": got})
    return {"right": right, "total": total, "wrong": wrong}


def judge_score(url: str, model: str, paths: list[str]) -> dict:
    right, wrong = {}, []
    for path in paths:
        for line in open(path, encoding="utf-8"):
            if not line.strip():
                continue
            item = json.loads(line)
            state, question = judge_question(item["q"], item)
            got = pick(decide(url, model, state, {"q": question})["q"])
            right.setdefault(item["q"], 0)
            if got == ("true" if int(item["label"]) else "false"):
                right[item["q"]] += 1
            else:
                wrong.append({"q": item["q"], "text": (item.get("question") or item.get("statement") or state)[:120], "want": item["label"], "got": got})
    return {"right": right, "wrong": wrong}


def replay_agreement(url: str, base: str, candidate: str, path: str, skip: set[str] = frozenset()) -> dict:
    """[skip]: 정답 시험셋으로 따로 재는 판정 (지금 모델과 달라지는 것이 목표라 일치율에 넣지 않는다)"""
    same, total, differ = 0, 0, []
    for line in open(path, encoding="utf-8"):
        if not line.strip():
            continue
        item = json.loads(line)
        if item["q"] in skip:
            continue
        state, question = judge_question(item["q"], item)
        a = pick(decide(url, base, state, {"q": question})["q"])
        b = pick(decide(url, candidate, state, {"q": question})["q"])
        total += 1
        if a == b:
            same += 1
        else:
            differ.append({"q": item["q"], "state": state[:120], "base": a, "candidate": b})
    return {"same": same, "total": total, "differ": differ}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True, help="지금 Ollaya 모델 (예: winnow:e4b)")
    p.add_argument("--candidate", required=True, help="새 Ollaya 모델")
    p.add_argument("--test", nargs="+", required=True)
    p.add_argument("--judge-test", nargs="*", default=[], help="정답이 붙은 판정 시험셋 JSONL (학습에 쓰지 않는다)")
    p.add_argument("--replay", required=True)
    p.add_argument("--min-replay", type=float, default=0.95)
    p.add_argument("--ollaya", default="http://127.0.0.1:11435")
    args = p.parse_args()
    base = routing_score(args.ollaya, args.base, args.test)
    cand = routing_score(args.ollaya, args.candidate, args.test)
    judge_base = judge_score(args.ollaya, args.base, args.judge_test)
    judge_cand = judge_score(args.ollaya, args.candidate, args.judge_test)
    replay = replay_agreement(args.ollaya, args.base, args.candidate, args.replay, set(judge_base["right"]))
    # 라우팅(D1·D2·D3)과 판정(D6·D7·D10)을 한 표로: 이름이 겹치지 않는다
    base_right = {**base["right"], **judge_base["right"]}
    cand_right = {**cand["right"], **judge_cand["right"]}
    gained = sum(cand_right.values()) - sum(base_right.values())
    no_drop = all(cand_right[k] >= base_right[k] for k in base_right)
    replay_rate = replay["same"] / max(replay["total"], 1)
    passed = gained > 0 and no_drop and replay_rate >= args.min_replay
    reasons = []
    if gained <= 0:
        reasons.append("정답 수가 늘지 않음")
    if not no_drop:
        reasons.append("정답 수가 줄어든 질문이 있음: " + ", ".join(k for k in base_right if cand_right[k] < base_right[k]))
    if replay_rate < args.min_replay:
        reasons.append(f"다른 판정 일치율 {replay_rate:.0%} < {args.min_replay:.0%}")
    print(json.dumps({
        "event": "gate", "passed": passed, "reasons": reasons, "gained": gained,
        "base": {"model": args.base, "right": base_right, "total": base["total"]},
        "candidate": {"model": args.candidate, "right": cand_right, "total": cand["total"], "wrong": cand["wrong"] + judge_cand["wrong"]},
        "judge": {"total": {k: v + len([w for w in judge_base["wrong"] if w["q"] == k]) for k, v in judge_base["right"].items()}},
        "replay": {"same": replay["same"], "total": replay["total"], "differ": replay["differ"]},
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
