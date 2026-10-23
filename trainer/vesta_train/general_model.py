"""메인 모델(gemma4, Ollama MLX 형식) 읽기·내보내기 (PROJECT.md L-24).
Ollama는 MLX 모델을 텐서마다 safetensors blob 하나로 둔다(mxfp8: U32 묶음 + `.weight.scale`). 원본 가중치를 받지 않고 그 blob을 링크로 묶어 mlx-lm으로 읽는다.
내보낼 때는 LoRA를 붙인 층만 BF16으로 합쳐 새 blob으로 쓰고, 나머지 blob은 그대로 둔 새 매니페스트를 Ollama 저장소에 만든다.
mxfp8로 다시 양자화하면 LoRA 변화가 양자화 간격에 묻힌다 (L-24 시험: 실제로 바뀐 값과 LoRA 변화의 상관 0.31).
"""
import hashlib
import json
import os
from pathlib import Path

import mlx.core as mx

QUANTIZATION = {"group_size": 32, "bits": 8, "mode": "mxfp8"}
TURN_END = "<turn|>"
# 생각을 끈 답은 빈 생각 칸으로 시작한다. Ollama 렌더러는 시스템 프롬프트가 있으면 이 칸을 미리 채우지 않고(토큰 수로 확인: 66 = 채우지 않은 꼴)
# 모델이 직접 쓰게 한다. 그래서 이 칸은 답 쪽에 넣어 학습한다 (프롬프트에 넣고 학습하면 이 칸을 쓰는 법을 잃어 Ollama에서 빈 답이 나왔다)
EMPTY_THOUGHT = "<|channel>thought\n<channel|>"


def prompt(system: str, user: str) -> str:
    """Vesta가 보내는 꼴(시스템 프롬프트 + 질문, 생각 끔)을 Ollama가 렌더링한 것과 같게"""
    return f"<bos><|turn>system\n{system}{TURN_END}\n<|turn>user\n{user}{TURN_END}\n<|turn>model\n"


def completion(answer: str) -> str:
    return EMPTY_THOUGHT + answer.strip() + TURN_END


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


def export(model, models: Path, base: str, name: str, work: Path) -> dict:
    """LoRA 층을 합쳐 BF16 blob으로, 새 매니페스트 [name]을 Ollama 저장소에 쓴다"""
    from mlx_lm.tuner.lora import LoRALinear
    manifest = json.loads(manifest_path(models, base).read_text())
    by_name = {l.get("name"): l for l in manifest["layers"] if l.get("name")}
    changed = 0
    work.mkdir(parents=True, exist_ok=True)
    for path, module in model.named_modules():
        if not isinstance(module, LoRALinear):
            continue
        fused = module.fuse(dequantize=True)
        tensor = ollama_name(path)
        layer = by_name[tensor]
        file = work / "fused.safetensors"
        mx.save_safetensors(str(file), {tensor: fused.weight.astype(mx.bfloat16)})
        data = file.read_bytes()
        digest = "sha256:" + hashlib.sha256(data).hexdigest()
        blob = models / "blobs" / digest.replace(":", "-")
        if not blob.exists():
            tmp = blob.with_suffix(".partial")
            tmp.write_bytes(data)
            tmp.rename(blob)
        layer.update({"digest": digest, "size": len(data)})
        changed += 1
    target = manifest_path(models, name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(manifest))
    return {"tensors": changed, "manifest": str(target)}
