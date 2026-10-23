"""결정 모델(Winnow) LoRA 미세조정 (PROJECT.md 7.10 L-03).
손실은 마지막 위치의 선택지 글자 logit에 대한 교차 엔트로피 (Ollaya가 판정하는 방식 그대로).
진행 상황은 표준 출력에 JSON 한 줄씩 낸다: {"event": "progress", "step", "total", "loss"} — Vesta가 읽어 화면에 보여 준다 (L-05).
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

from .data import judge_examples, replay_examples, routing_examples
from .model import MAX_OPTIONS, Winnow, save_adapter

KEYS = ["self_attn.q_proj", "self_attn.k_proj", "self_attn.v_proj", "self_attn.o_proj", "mlp.gate_proj", "mlp.up_proj", "mlp.down_proj"]


def emit(**event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True, help="HF 형식 Winnow (convert로 만든 폴더)")
    p.add_argument("--train", required=True, nargs="+", help="라우팅 학습 TSV")
    p.add_argument("--judge", nargs="*", default=[], help="정답이 붙은 판정 재료 JSONL (D6·D7·D10, L-22)")
    p.add_argument("--replay", help="판정 재연 JSONL (지금 모델 답을 지키게)")
    p.add_argument("--out", required=True)
    p.add_argument("--rank", type=int, default=16)
    p.add_argument("--alpha", type=float, default=32)
    # M14 실험: 1e-4는 몇십 단계 만에 손실이 0.19 → 1 안팎으로 올라 모델을 흐트러뜨렸다
    p.add_argument("--lr", type=float, default=2e-5)
    p.add_argument("--warmup", type=int, default=20, help="학습률을 0에서 올리는 단계 수")
    p.add_argument("--eval-every", type=int, default=50, help="이 단계마다 확인용 손실·정답률")
    p.add_argument("--max-steps", type=int, default=0, help="실험용: 이 단계에서 멈춘다 (0이면 끝까지)")
    p.add_argument("--epochs", type=int, default=2)
    p.add_argument("--batch", type=int, default=8)
    # L-22: 코드 판정 재료는 2,000토큰이 넘어 8개 묶음(약 2만 토큰)이 메모리를 넘겨 강제 종료됐다(r3, 종료 코드 137)
    p.add_argument("--batch-tokens", type=int, default=4096, help="묶음 하나의 최대 토큰 수 (가장 긴 문항 길이 × 문항 수)")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--ollaya", default="http://127.0.0.1:11435")
    args = p.parse_args()
    random.seed(args.seed)
    mx.random.seed(args.seed)

    examples = [e for path in args.train for e in routing_examples(path)] + [e for path in args.judge for e in judge_examples(path)]
    # 학습 문항의 10%는 손실 확인용 (문항 단위로 나눠 같은 질문이 양쪽에 들어가지 않게)
    dev = [e for e in examples if int(hashlib.sha256(e.source.encode()).hexdigest(), 16) % 10 == 0]
    train = [e for e in examples if e not in dev]
    if args.replay:
        replay_train, _ = replay_examples(args.replay, url=args.ollaya)
        train += replay_train
        emit(event="data", routing=len(examples), replay=len(replay_train))
    emit(event="data", train=len(train), dev=len(dev))

    w = Winnow(args.base)
    model = w.model
    model.freeze()
    lora = {"rank": args.rank, "scale": args.alpha / args.rank, "dropout": 0.0, "keys": KEYS}
    linear_to_lora_layers(model, len(model.layers), lora)
    trainable = sum(v.size for _, v in __import__("mlx.utils", fromlist=["tree_flatten"]).tree_flatten(model.trainable_parameters()))
    emit(event="model", trainable=trainable)

    lengths = {}

    def length(e):
        if e.prompt not in lengths:
            lengths[e.prompt] = len(w.tokens(e.prompt))
        return lengths[e.prompt]

    def plan(items, shuffle):
        """문항 수 [batch]개, 채운 길이 [batch_tokens] 안에서 묶는다. 긴 문항은 혼자 한 묶음"""
        order = list(items)
        if shuffle:
            random.shuffle(order)
        out, current, longest = [], [], 0
        for e in order:
            n = length(e)
            if current and (len(current) >= args.batch or max(longest, n) * (len(current) + 1) > args.batch_tokens):
                out.append(current)
                current, longest = [], 0
            current.append(e)
            longest = max(longest, n)
        if current:
            out.append(current)
        return out

    def batches(items, shuffle):
        yield from plan(items, shuffle)

    def loss_fn(_model, tokens, answers, mask):
        logits = w.option_logits(tokens).astype(mx.float32)
        logits = mx.where(mask, logits, -1e9)
        return nn.losses.cross_entropy(logits, answers, reduction="mean")

    def tensors(batch):
        tokens = [w.tokens(e.prompt) for e in batch]
        answers = mx.array([e.answer for e in batch])
        mask = mx.array([[i < e.count for i in range(MAX_OPTIONS)] for e in batch])
        return tokens, answers, mask

    def evaluate(items):
        right, total, loss = 0, 0, 0.0
        for batch in batches(items, False):
            tokens, answers, mask = tensors(batch)
            logits = mx.where(mask, w.option_logits(tokens).astype(mx.float32), -1e9)
            loss += float(nn.losses.cross_entropy(logits, answers, reduction="sum"))
            right += int((mx.argmax(logits, axis=-1) == answers).sum())
            total += len(batch)
        return loss / max(total, 1), right / max(total, 1)

    steps = args.epochs * len(plan(train, False))
    if args.max_steps:
        steps = min(steps, args.max_steps)
    warmup = min(args.warmup, max(steps // 5, 1))
    schedule = optim.join_schedules(
        [optim.linear_schedule(1e-7, args.lr, warmup), optim.cosine_decay(args.lr, max(steps - warmup, 1), end=args.lr * 0.1)],
        [warmup],
    )
    optimizer = optim.AdamW(learning_rate=schedule, weight_decay=0.0)
    grad_fn = nn.value_and_grad(model, loss_fn)
    dev_loss, dev_acc = evaluate(dev)
    emit(event="dev", step=0, loss=round(dev_loss, 4), accuracy=round(dev_acc, 4))
    step, started, recent = 0, time.time(), []
    for epoch in range(args.epochs):
        model.train()
        for batch in batches(train, True):
            if step >= steps:
                break
            tokens, answers, mask = tensors(batch)
            loss, grads = grad_fn(model, tokens, answers, mask)
            optimizer.update(model, grads)
            mx.eval(model.parameters(), optimizer.state, loss)
            step += 1
            recent = (recent + [float(loss)])[-20:]
            # 배치마다 들쭉날쭉하므로 최근 20단계 평균을 보여 준다
            emit(event="progress", step=step, total=steps, loss=round(sum(recent) / len(recent), 4), elapsed=round(time.time() - started))
            if args.eval_every and step % args.eval_every == 0 and step < steps:
                model.eval()
                dev_loss, dev_acc = evaluate(dev)
                emit(event="dev", step=step, loss=round(dev_loss, 4), accuracy=round(dev_acc, 4))
                model.train()
        model.eval()
        dev_loss, dev_acc = evaluate(dev)
        emit(event="dev", step=step, epoch=epoch + 1, loss=round(dev_loss, 4), accuracy=round(dev_acc, 4))
        if step >= steps:
            break
    config = {"fine_tune_type": "lora", "num_layers": len(model.layers), "lora_parameters": lora, "base": args.base}
    save_adapter(model, Path(args.out), config)
    emit(event="saved", path=args.out)


if __name__ == "__main__":
    sys.exit(main())
