"""메인 모델(gemma4, Ollama MLX 형식) 읽기·내보내기 (PROJECT.md L-24).
Ollama는 MLX 모델을 텐서마다 safetensors blob 하나로 둔다(mxfp8: U32 묶음 + `.weight.scale`). 원본 가중치를 받지 않고 그 blob을 링크로 묶어 mlx-lm으로 읽는다.
내보낼 때는 LoRA를 붙인 층만 합쳐 새 blob으로 쓰고, 나머지 blob은 그대로 둔 새 매니페스트를 Ollama 저장소에 만든다.
mxfp8로 다시 양자화하면 LoRA 변화가 양자화 간격에 묻힌다 (L-24 시험: 실제로 바뀐 값과 LoRA 변화의 상관 0.31) → BF16(기본) 또는 affine int8.
"""
import hashlib
import json
import os
from pathlib import Path

import mlx.core as mx

QUANTIZATION = {"group_size": 32, "bits": 8, "mode": "mxfp8"}
TURN_END = "<turn|>"
# Ollama의 gemma4 "large" 렌더러(12b·26b·31b, 생각 끔)와 똑같은 꼴: 빈 생각 칸을 미리 채운다 (토큰 수로 확인: /v1 70 = 이 꼴).
# Ollama는 렌더러를 **모델 이름**으로 고른다(server/renderer_resolution.go: 이름에 26b가 있으면 large, 없으면 small).
# 그래서 후보 이름에도 원본 태그(26b-mxfp8)를 넣는다. 처음 시험 후보(gemma4-vesta:smoke)는 small로 렌더링돼 빈 답을 냈다
EMPTY_THOUGHT = "<|channel>thought\n<channel|>"


def prompt(system: str, user: str) -> str:
    return f"<bos><|turn>system\n{system}{TURN_END}\n<|turn>user\n{user}{TURN_END}\n<|turn>model\n{EMPTY_THOUGHT}"


def completion(answer: str) -> str:
    return answer.strip() + TURN_END


def manifest_path(models: Path, name: str) -> Path:
    repo, tag = name.split(":") if ":" in name else (name, "latest")
    return models / "manifests/registry.ollama.ai/library" / repo / tag


def assemble(models: Path, name: str, out: Path) -> Path:
    """Ollama 매니페스트 → mlx-lm이 읽는 폴더 (blob 링크)"""
    manifest = json.loads(manifest_path(models, name).read_text())
    out.mkdir(parents=True, exist_ok=True)
    for i, layer in enumerate(manifest["layers"]):
        blob = models / "blobs" / layer["digest"].replace(":", "-")
        kind, file = layer["mediaType"], layer.get("name")
        if kind.endswith(".tensor"):
            target = out / f"model-{i:05d}.safetensors"
        elif kind.endswith(".json") and file and "/" not in file:
            target = out / file
        else:
            continue
        if target.is_symlink() or target.exists():
            target.unlink()
        os.symlink(blob, target)
    return out


def _sanitize(weights: dict) -> dict:
    """Ollama 이름 → mlx-lm gemma4 이름: `.weight.scale` → `.scales`, 전문가 묶음 나누기. 투기 디코딩용 draft 모델은 뺀다"""
    out = {}
    for k, v in weights.items():
        if k.startswith("draft."):
            continue
        if k.endswith(".weight.scale"):
            k = k[: -len(".weight.scale")] + ".scales"
        handled = False
        for part in ("gate_up_proj", "down_proj"):
            for suffix in (".weight", ".scales"):
                key = f".experts.{part}{suffix}"
                if k.endswith(key):
                    base = k[: -len(key)] + ".experts.switch_glu"
                    if part == "gate_up_proj":
                        gate, up = map(mx.contiguous, mx.split(v, 2, axis=-2))
                        out[f"{base}.gate_proj{suffix}"] = gate
                        out[f"{base}.up_proj{suffix}"] = up
                    else:
                        out[f"{base}.down_proj{suffix}"] = v
                    handled = True
        if not handled:
            out[k] = v
    return out


def load(directory: Path):
    from mlx_lm import load as mlx_load
    from mlx_lm.models import gemma4
    original = gemma4.Model.sanitize
    if not getattr(original, "_vesta", False):
        def sanitize(self, weights):
            return original(self, _sanitize(weights))
        sanitize._vesta = True
        gemma4.Model.sanitize = sanitize
    return mlx_load(str(directory), model_config={"quantization": QUANTIZATION})


def ollama_name(path: str) -> str:
    """mlx 경로(language_model.model.layers.0.self_attn.q_proj) → Ollama 텐서 이름"""
    assert path.startswith("language_model.model."), path
    return "model.language_model." + path[len("language_model.model."):] + ".weight"


# 합친 층을 내보내는 형식: bf16(정확, 기본. 생성 속도 104.4 / 원본 104.9 tok/s, 크기 +2GB) 또는
# int8(affine 8비트·묶음 32, Ollama가 텐서마다 quant_type으로 읽는다. 시험에서 LoRA 변화의 상관 0.49만 남음)
EXPORT_GROUP = 32


def export(model, models: Path, base: str, name: str, work: Path, mode: str = "bf16") -> dict:
    """LoRA 층을 합쳐 새 blob으로, 새 매니페스트 [name]을 Ollama 저장소에 쓴다. [kept]: 실제로 바뀐 값과 LoRA 변화의 상관 (양자화로 잃은 정도)"""
    from mlx_lm.tuner.lora import LoRALinear
    manifest = json.loads(manifest_path(models, base).read_text())
    by_name = {l.get("name"): l for l in manifest["layers"] if l.get("name")}
    changed, kept = 0, []
    work.mkdir(parents=True, exist_ok=True)
    for path, module in model.named_modules():
        if not isinstance(module, LoRALinear):
            continue
        linear = module.linear
        original = mx.dequantize(linear.weight, linear.scales, getattr(linear, "biases", None), group_size=linear.group_size, bits=linear.bits, mode=linear.mode)
        fused = module.fuse(dequantize=True).weight.astype(mx.float32)
        tensor = ollama_name(path)
        if mode == "int8":
            wq, scales, biases = mx.quantize(fused, group_size=EXPORT_GROUP, bits=8, mode="affine")
            after = mx.dequantize(wq, scales, biases, group_size=EXPORT_GROUP, bits=8, mode="affine")
            arrays = {tensor: wq, tensor + ".scale": scales.astype(mx.bfloat16), tensor + ".bias": biases.astype(mx.bfloat16)}
            metadata = {"quant_type": "int8", "group_size": str(EXPORT_GROUP)}
        else:
            after = fused.astype(mx.bfloat16).astype(mx.float32)
            arrays, metadata = {tensor: fused.astype(mx.bfloat16)}, None
        delta, actual = (fused - original).flatten(), (after - original).flatten()
        kept.append(float((delta * actual).sum() / (mx.sqrt((delta * delta).sum() * (actual * actual).sum()) + 1e-12)))
        file = work / "fused.safetensors"
        mx.save_safetensors(str(file), arrays, metadata=metadata) if metadata else mx.save_safetensors(str(file), arrays)
        data = file.read_bytes()
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        blob = models / "blobs" / digest.replace(":", "-")
        if not blob.exists():
            tmp = blob.with_suffix(".partial")
            tmp.write_bytes(data)
            tmp.rename(blob)
        by_name[tensor].update({"digest": digest, "size": len(data)})
        changed += 1
    target = manifest_path(models, name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(manifest))
    return {"tensors": changed, "manifest": str(target), "export": mode, "kept": round(sum(kept) / max(len(kept), 1), 4)}
