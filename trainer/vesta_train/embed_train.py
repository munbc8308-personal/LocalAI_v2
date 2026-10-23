"""임베딩 모델(bge-m3) 미세조정 (PROJECT.md L-23).
재료: {"query", "positive", "negatives": [...]} JSONL (Vesta가 색인과 같은 꼴로 만든다).
손실: 질문 ↔ 조각 대조 학습(InfoNCE). 같은 묶음의 다른 정답 조각과 어려운 오답 조각을 오답으로 쓴다.
진행 상황은 {"event": "progress", "step", "total", "loss"} JSON 줄로 낸다. 끝나면 기본 GGUF 틀에 가중치를 써서 [--out]에 둔다
"""
import argparse
import json
import math
import random
import sys
import time
from pathlib import Path

import torch

from .embed_model import encode, load, save, tokenizer_dir


def emit(**event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gguf", required=True, help="기본 임베딩 모델 GGUF (Ollama bge-m3)")
    p.add_argument("--pairs", required=True)
    p.add_argument("--work", required=True, help="토크나이저를 받아 둘 곳")
    p.add_argument("--out", required=True, help="새 GGUF")
    p.add_argument("--epochs", type=int, default=2)
    p.add_argument("--lr", type=float, default=1e-5)
    p.add_argument("--batch", type=int, default=16)
    # bge 미세조정 권장값. 작을수록 정답과 오답을 세게 가른다
    p.add_argument("--temperature", type=float, default=0.02)
    p.add_argument("--query-length", type=int, default=128)
    p.add_argument("--passage-length", type=int, default=512)
    p.add_argument("--seed", type=int, default=7)
    args = p.parse_args()
    random.seed(args.seed)
    torch.manual_seed(args.seed)

    pairs = [json.loads(l) for l in open(args.pairs, encoding="utf-8") if l.strip()]
    if not pairs:
        raise SystemExit("학습 재료가 없습니다")
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model, tokenizer = load(args.gguf, tokenizer_dir(Path(args.work)))
    model.to(device)
    model.gradient_checkpointing_enable()
    emit(event="data", pairs=len(pairs), negatives=sum(len(x.get("negatives", [])) for x in pairs), device=device)

    steps = args.epochs * math.ceil(len(pairs) / args.batch)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    warmup = max(1, steps // 10)
    schedule = torch.optim.lr_scheduler.LambdaLR(optimizer, lambda s: min(1.0, (s + 1) / warmup) * max(0.1, 1 - s / steps))
    step, started, recent = 0, time.time(), []
    model.train()
    for _ in range(args.epochs):
        random.shuffle(pairs)
        for i in range(0, len(pairs), args.batch):
            batch = pairs[i:i + args.batch]
            # 정답 조각들 + 어려운 오답 조각들. 같은 조각이 두 번 들어가면 정답끼리 오답이 되므로 글로 중복을 지운다
            passages, index = [], {}
            for item in batch:
                for text in [item["positive"]] + item.get("negatives", []):
                    if text not in index:
                        index[text] = len(passages)
                        passages.append(text)
            q = encode(model, tokenizer, [x["query"] for x in batch], args.query_length, device)
            d = encode(model, tokenizer, passages, args.passage_length, device)
            logits = q @ d.T / args.temperature
            # 다른 질문의 정답 조각이 이 질문에도 정답일 수는 있다 (같은 조각 = 같은 번호라 그 경우는 없다)
            target = torch.tensor([index[x["positive"]] for x in batch], device=device)
            loss = torch.nn.functional.cross_entropy(logits, target)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            schedule.step()
            optimizer.zero_grad()
            step += 1
            recent = (recent + [float(loss)])[-20:]
            emit(event="progress", step=step, total=steps, loss=round(sum(recent) / len(recent), 4), elapsed=round(time.time() - started))
    model.eval()
    model.to("cpu")
    save(args.gguf, model, args.out)
    emit(event="saved", path=args.out)


if __name__ == "__main__":
    sys.exit(main())
