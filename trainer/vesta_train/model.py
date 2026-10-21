"""MLX Winnow 모델: Ollaya(llama.cpp)와 같은 토큰화(<bos> + 프롬프트)와 선택지 글자 logit"""
import json
from pathlib import Path

import mlx.core as mx
import mlx.nn as nn
from mlx_lm import load

from .winnow_prompt import LABELS

# Winnow Q8_0의 보정 온도 (Ollaya calibration 레이어). 판정(가장 큰 값)은 바꾸지 않는다
TEMPERATURE = 1.2574172017327816
MAX_OPTIONS = 11


class Winnow:
    def __init__(self, path: str, adapter: str | None = None):
        self.model, self.tokenizer = load(path, adapter_path=adapter)
        ids = [self.tokenizer.encode(l, add_special_tokens=False) for l in LABELS[:MAX_OPTIONS]]
        assert all(len(i) == 1 for i in ids), ids
        self.label_ids = mx.array([i[0] for i in ids])
        self.bos = self.tokenizer.bos_token_id

    def tokens(self, text: str) -> list[int]:
        """llama.cpp처럼 맨 앞에 <bos>를 붙인다 (HF 토크나이저는 이 형식에서 붙이지 않는다)"""
        ids = self.tokenizer.encode(text, add_special_tokens=False)
        return ids if ids and ids[0] == self.bos else [self.bos] + ids

    def option_logits(self, batch: list[list[int]]) -> mx.array:
        """[B, MAX_OPTIONS]. 오른쪽을 채운 묶음이라 인과 마스크로 충분하다 (실제 마지막 위치는 채움 토큰을 보지 않는다)"""
        length = max(len(t) for t in batch)
        padded = mx.array([t + [0] * (length - len(t)) for t in batch])
        hidden = self.model.model(padded)                                   # [B, L, H] (마지막 norm 포함)
        last = mx.array([len(t) - 1 for t in batch])
        h = mx.take_along_axis(hidden, last[:, None, None], axis=1)[:, 0, :]  # [B, H]
        weights = self.model.model.embed_tokens.weight[self.label_ids]      # [K, H] (출력층은 임베딩 공유)
        logits = h @ weights.T
        cap = self.model.final_logit_softcapping
        return mx.tanh(logits / cap) * cap if cap else logits

    def probabilities(self, text: str, count: int) -> list[float]:
        logits = self.option_logits([self.tokens(text)])[0, :count].astype(mx.float32) / TEMPERATURE
        return mx.softmax(logits).tolist()


def save_adapter(model: nn.Module, directory: Path, config: dict) -> None:
    """mlx_lm.load(adapter_path=…)로 다시 읽을 수 있는 형식"""
    from mlx.utils import tree_flatten
    directory.mkdir(parents=True, exist_ok=True)
    mx.save_safetensors(str(directory / "adapters.safetensors"), dict(tree_flatten(model.trainable_parameters())))
    (directory / "adapter_config.json").write_text(json.dumps(config, indent=2))
