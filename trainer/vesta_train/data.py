"""학습·평가 데이터 (PROJECT.md 7.10 L-02). 라우팅 TSV와 판정 재연(replay) 항목을 Winnow 프롬프트 + 정답 선택지 번호로 만든다"""
import hashlib
import json
import urllib.request
from dataclasses import dataclass

from .winnow_prompt import QUESTIONS, judge_question, options, prompt


@dataclass
class Example:
    prompt: str
    answer: int          # 정답 선택지 번호 (A=0)
    count: int           # 선택지 수
    kind: str            # agent / needsWeb / needsLocal / D4 …
    source: str          # 원문 (보고서용)


def routing_rows(path: str) -> list[list[str]]:
    return [l.rstrip("\n").split("\t") for l in open(path, encoding="utf-8") if l.strip() and not l.startswith("#")]


def routing_examples(path: str) -> list[Example]:
    out = []
    for question, agent, web, local in routing_rows(path):
        q = QUESTIONS["agent"]
        out.append(Example(prompt(question, q), options(q).index(agent), len(options(q)), "agent", question))
        # 사용자 정정(corrections.tsv)은 담당자만 알고 웹·로컬 필요는 모른다("-")
        if web != "-":
            out.append(Example(prompt(question, QUESTIONS["needsWeb"]), int(web), 2, "needsWeb", question))
        if local != "-":
            out.append(Example(prompt(question, QUESTIONS["needsLocal"]), int(local), 2, "needsLocal", question))
    return out


def teacher(state: str, question: dict, model: str, url: str) -> list[float]:
    """지금 결정 모델(Ollaya)의 선택지 확률 — 재연 정답"""
    body = {"model": model, "state": state, "questions": {"q": question}}
    answer = json.load(urllib.request.urlopen(urllib.request.Request(f"{url}/api/decide", json.dumps(body).encode(), {"Content-Type": "application/json"})))["answers"]["q"]
    if question["type"] == "noul":
        return [1 - answer["noul"], answer["noul"]]
    return [answer["probabilities"].get(k, 0.0) for k in options(question)]


def replay_examples(path: str, model: str = "winnow:e4b", url: str = "http://127.0.0.1:11435", min_confidence: float = 0.8) -> tuple[list[Example], list[Example]]:
    """(학습용, 비교용). 지금 모델이 확신하는 답만 쓰고, 항목마다 고정된 해시로 7:3으로 나눈다"""
    train, holdout = [], []
    for line in open(path, encoding="utf-8"):
        if not line.strip():
            continue
        item = json.loads(line)
        state, question = judge_question(item["q"], item)
        probs = teacher(state, question, model, url)
        best = max(range(len(probs)), key=probs.__getitem__)
        if probs[best] < min_confidence:
            continue
        example = Example(prompt(state, question), best, len(probs), item["q"], line.strip())
        bucket = int(hashlib.sha256(line.encode()).hexdigest(), 16) % 10
        (holdout if bucket < 3 else train).append(example)
    return train, holdout
