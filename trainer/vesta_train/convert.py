"""Winnow GGUF → HF safetensors(BF16), MLX 학습용 (PROJECT.md 7.10 L-03).

transformers(5.20)의 Gemma4 GGUF 로더로 텐서를 풀되, 두 가지는 직접 맞춘다 (M14에서 확인):
- 설정: GGUF 메타데이터로 만든 설정은 num_kv_shared_layers(18)·final_logit_softcapping(30)이 빠져 판정이 틀린다
  → 공식 google/gemma-4-E4B-it 설정(data/gemma-4-E4B-it-config.json)의 text_config를 쓴다.
- layer_scalar: 변환표에 없어 1로 남는다 → GGUF의 blk.N.layer_output_scale 값을 넣는다.
이렇게 만든 모델은 Ollaya(llama.cpp)와 라우팅 판정 210개가 모두 같았다.
"""
import argparse
import json
import time

import gguf
import numpy as np
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, Gemma4TextConfig
from transformers.modeling_gguf_pytorch_utils import load_gguf_checkpoint


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gguf", required=True, help="Winnow GGUF 파일")
    p.add_argument("--config", required=True, help="공식 gemma-4-E4B-it config.json")
    p.add_argument("--out", required=True)
    args = p.parse_args()
    started = time.time()
    folder, name = args.gguf.rsplit("/", 1)
    tok = AutoTokenizer.from_pretrained(folder, gguf_file=name)
    # 텐서 이름 매핑을 얻으려고 한 번 읽는다 (가중치는 아래에서 다시 풀어 쓴다)
    packed = AutoModelForCausalLM.from_pretrained(folder, gguf_file=name, dtype=torch.bfloat16)
    tensors = load_gguf_checkpoint(args.gguf, return_tensors=True, model_to_load=packed, torch_dtype=torch.bfloat16)["tensors"]
    del packed
    for t in gguf.GGUFReader(args.gguf).tensors:
        if t.name.endswith("layer_output_scale.weight"):
            layer = int(t.name.split(".")[1])
            tensors[f"model.layers.{layer}.layer_scalar"] = torch.tensor(np.array(t.data, dtype=np.float32).reshape(-1), dtype=torch.bfloat16)
    config = Gemma4TextConfig(**json.load(open(args.config))["text_config"])
    config.architectures = ["Gemma4ForCausalLM"]
    model = AutoModelForCausalLM.from_config(config, dtype=torch.bfloat16)
    result = model.load_state_dict(tensors, strict=False)
    # 출력층은 임베딩을 공유한다. KV를 공유하는 뒤쪽 레이어의 k/v 가중치는 HF 모델에 없어 남는다 (쓰이지 않음)
    assert result.missing_keys == ["lm_head.weight"] or not result.missing_keys, result.missing_keys
    assert all(".self_attn.k_" in k or ".self_attn.v_" in k for k in result.unexpected_keys), result.unexpected_keys[:5]
    model.save_pretrained(args.out, safe_serialization=True)
    tok.save_pretrained(args.out)
    print(json.dumps({"event": "converted", "path": args.out, "seconds": round(time.time() - started)}))


if __name__ == "__main__":
    main()
