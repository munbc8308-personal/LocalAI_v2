"""임베딩 모델(bge-m3) GGUF ↔ HF 변환 (PROJECT.md L-23).
Ollama의 bge-m3 GGUF(F16, bert 구조, CLS 풀링)를 HF XLMRobertaModel로 풀어 학습하고, 학습한 가중치를 같은 GGUF 틀(메타데이터·토크나이저 그대로)에 다시 쓴다.
원본 가중치는 받지 않는다. 토크나이저 파일만 BAAI/bge-m3(MIT)에서 한 번 받는다 (GGUF의 토크나이저를 HF 형식으로 되돌리기 어렵다).
"""
import json
from pathlib import Path

import numpy as np
import torch
from gguf import GGUFReader, GGUFWriter, GGUFValueType, GGMLQuantizationType

HF_REPO = "BAAI/bge-m3"
# 받는 토크나이저 버전을 고정한다
HF_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
TOKENIZER_FILES = ["config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "sentencepiece.bpe.model"]

LAYER = {
    "attn_q": "attention.self.query",
    "attn_k": "attention.self.key",
    "attn_v": "attention.self.value",
    "attn_output": "attention.output.dense",
    "attn_output_norm": "attention.output.LayerNorm",
    "ffn_up": "intermediate.dense",
    "ffn_down": "output.dense",
    "layer_output_norm": "output.LayerNorm",
}
TOP = {
    "token_embd": "embeddings.word_embeddings",
    "token_embd_norm": "embeddings.LayerNorm",
}
# XLM-R 위치는 padding_idx(1) + 1부터 쓴다. llama.cpp 변환은 앞 두 줄을 버린다
POSITION_OFFSET = 2


def hf_name(gguf_name: str) -> str | None:
    """GGUF 텐서 이름 → HF 이름 (위치·토큰 종류는 따로)"""
    stem, suffix = gguf_name.rsplit(".", 1)
    if stem.startswith("blk."):
        _, layer, part = stem.split(".", 2)
        return f"encoder.layer.{layer}.{LAYER[part]}.{suffix}" if part in LAYER else None
    return f"{TOP[stem]}.{suffix}" if stem in TOP else None


def tokenizer_dir(work: Path) -> Path:
    from huggingface_hub import hf_hub_download
    target = work / "bge-m3-tokenizer"
    for name in TOKENIZER_FILES:
        if not (target / name).exists():
            hf_hub_download(HF_REPO, name, revision=HF_REVISION, local_dir=target)
    return target


def load(gguf_path: str, tokenizer: Path):
    """GGUF → (XLMRobertaModel float32, tokenizer)"""
    from transformers import AutoTokenizer, XLMRobertaConfig, XLMRobertaModel
    config = XLMRobertaConfig.from_pretrained(tokenizer)
    config.add_pooling_layer = False
    model = XLMRobertaModel(config, add_pooling_layer=False)
    state = model.state_dict()
    reader = GGUFReader(gguf_path)
    seen = set()
    for t in reader.tensors:
        data = torch.from_numpy(np.array(t.data, dtype=np.float32))
        if t.name == "position_embd.weight":
            weight = torch.zeros_like(state["embeddings.position_embeddings.weight"])
            weight[POSITION_OFFSET:POSITION_OFFSET + data.shape[0]] = data
            state["embeddings.position_embeddings.weight"] = weight
            seen.add("embeddings.position_embeddings.weight")
        elif t.name == "token_types.weight":
            state["embeddings.token_type_embeddings.weight"] = data.reshape(1, -1)
            seen.add("embeddings.token_type_embeddings.weight")
        else:
            name = hf_name(t.name)
            if name is None:
                raise ValueError(f"모르는 텐서: {t.name}")
            assert state[name].shape == data.shape, (name, state[name].shape, data.shape)
            state[name] = data
            seen.add(name)
    missing = [k for k in state if k not in seen and not k.endswith("position_ids")]
    assert not missing, missing
    model.load_state_dict(state)
    return model, AutoTokenizer.from_pretrained(tokenizer)


def save(base_gguf: str, model, out: str) -> None:
    """[base_gguf]의 메타데이터·텐서 형식 그대로, 값만 [model]의 것으로 바꿔 쓴다"""
    state = {k: v.detach().to(torch.float32).cpu().numpy() for k, v in model.state_dict().items()}
    reader = GGUFReader(base_gguf)
    arch = reader.fields["general.architecture"].contents()
    writer = GGUFWriter(out, arch)
    for key, field in reader.fields.items():
        if key.startswith("GGUF.") or key == "general.architecture":
            continue
        kind = field.types[0]
        # 배열은 원소 형식을 그대로 (precompiled_charsmap은 UINT8 배열이라 추정하면 형식이 바뀌어 Ollama가 읽지 못한다)
        sub = field.types[1] if kind == GGUFValueType.ARRAY else None
        writer.add_key_value(key, field.contents(), kind, sub_type=sub)
    writer.add_string("general.name", "bge-m3-vesta")
    for t in reader.tensors:
        if t.name == "position_embd.weight":
            value = state["embeddings.position_embeddings.weight"][POSITION_OFFSET:POSITION_OFFSET + t.data.shape[0]]
        elif t.name == "token_types.weight":
            value = state["embeddings.token_type_embeddings.weight"].reshape(-1)
        else:
            value = state[hf_name(t.name)]
        if t.tensor_type == GGMLQuantizationType.F16:
            value = value.astype(np.float16)
        elif t.tensor_type == GGMLQuantizationType.F32:
            value = value.astype(np.float32)
        else:
            raise ValueError(f"지원하지 않는 형식: {t.name} {t.tensor_type}")
        writer.add_tensor(t.name, value, raw_dtype=t.tensor_type)
    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()


def encode(model, tokenizer, texts: list[str], max_length: int = 512, device: str = "cpu") -> torch.Tensor:
    """bge-m3 밀집 벡터: CLS, 길이 1로 맞춤 (Ollama pooling_type 2와 같다)"""
    batch = tokenizer(texts, padding=True, truncation=True, max_length=max_length, return_tensors="pt").to(device)
    hidden = model(**batch).last_hidden_state[:, 0]
    return torch.nn.functional.normalize(hidden, dim=-1)
