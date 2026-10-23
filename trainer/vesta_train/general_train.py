"""메인 모델 개인화 QLoRA (PROJECT.md L-24): mxfp8 원래 가중치는 얼리고 주의(attention)·밀집 MLP 층에만 LoRA (전문가 층·라우터는 그대로).
재료: {"system", "user", "answer"} JSONL (Vesta가 좋은 답 👍만, 근거·도구 없이 답한 일반 대화만 넣는다). 손실은 답 토큰에만.
진행 {"event": "progress"}·확인용 손실 {"event": "dev"}을 JSON 줄로 낸다. 끝나면 합쳐서 Ollama 모델 [--name]으로 등록한다
"""
import argparse
import hashlib
import json
import random
import sys
import time
from pathlib import Path

import mlx.core as mx
import mlx.nn as nn
import mlx.optimizers as optim
from mlx_lm.tuner.utils import linear_to_lora_layers

from .general_model import assemble, completion, export, load, prompt

KEYS = ["self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj", "mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]


def emit(**event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True, help="Ollama 모델 이름 (예: gemma4:26b-mxfp8)")
    p.add_argument("--models", required=True, help="Ollama 모델 저장소")
    p.add_argument("--data", required=True)
    p.add_argument("--work", required=True)
    p.add_argument("--name", required=True, help="등록할 Ollama 모델 이름")
    p.add_argument("--rank", type=int, default=16)
    p.add_argument("--alpha", type=float, default=32)
    p.add_argument("--lr", type=float, default=5e-5)
    p.add_argument("--epochs", type=int, default=2)
    p.add_argument("--max-tokens", type=int, default=2048)
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--export", choices=["bf16", "int8"], default="bf16", help="합친 층 형식")
    args = p.parse_args()
    random.seed(args.seed)
    mx.random.seed(args.seed)

    work = Path(args.work)
    model, tokenizer = load(assemble(Path(args.models), args.base, work / "base"))
    rows = [json.loads(l) for l in open(args.data, encoding="utf-8") if l.strip()]
    examples = []
    for r in rows:
        head = tokenizer.encode(prompt(r["system"], r["user"]), add_special_tokens=False)
        tail = tokenizer.encode(completion(r["answer"]), add_special_tokens=False)
        ids = (head + tail)[: args.max_tokens]
        if len(ids) > len(head):
            examples.append((ids, len(head), hashlib.sha256(r["user"].encode()).hexdigest()))
    dev = [e for e in examples if int(e[2], 16) % 10 == 0]
    train = [e for e in examples if e not in dev] or examples
    emit(event="data", train=len(train), dev=len(dev))

    model.freeze()
    lm = model.language_model
    lora = {"rank": args.rank, "scale": args.alpha / args.rank, "dropout": 0.0, "keys": KEYS}
    linear_to_lora_layers(lm, len(lm.layers), lora)

    def loss_fn(m, ids, start):
        x = mx.array([ids])
        logits = m(x[:, :-1]).astype(mx.float32)
        losses = nn.losses.cross_entropy(logits, x[:, 1:], reduction="none")[0]
        mask = mx.arange(losses.shape[0]) >= (start - 1)
        return (losses * mask).sum() / mask.sum()

    def evaluate(items):
        if not items:
            return None
        return sum(float(loss_fn(model, ids, start)) for ids, start, _ in items) / len(items)

    steps = args.epochs * len(train)
    warmup = max(1, steps // 10)
    schedule = optim.join_schedules([optim.linear_schedule(1e-7, args.lr, warmup), optim.cosine_decay(args.lr, max(steps - warmup, 1), end=args.lr * 0.1)], [warmup])
    optimizer = optim.AdamW(learning_rate=schedule, weight_decay=0.0)
    grad = nn.value_and_grad(model, loss_fn)
    emit(event="dev", step=0, loss=evaluate(dev))
    step, started, recent = 0, time.time(), []
    for epoch in range(args.epochs):
        model.train()
        random.shuffle(train)
        for ids, start, _ in train:
            loss, g = grad(model, ids, start)
            optimizer.update(model, g)
            mx.eval(model.parameters(), optimizer.state, loss)
            step += 1
            recent = (recent + [float(loss)])[-20:]
            emit(event="progress", step=step, total=steps, loss=round(sum(recent) / len(recent), 4), elapsed=round(time.time() - started), peak=round(mx.get_peak_memory() / 1e9, 1))
        model.eval()
        emit(event="dev", step=step, epoch=epoch + 1, loss=evaluate(dev))
    result = export(model, Path(args.models), args.base, args.name, work, args.export)
    emit(event="registered", name=args.name, samples=len(examples), **result)


if __name__ == "__main__":
    sys.exit(main())
