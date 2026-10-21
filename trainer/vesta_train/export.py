"""학습한 LoRA를 원본 Winnow GGUF에 합쳐 Ollaya 모델로 등록한다 (PROJECT.md 7.10 L-03·L-07).

- 원본 GGUF(Q8_0)를 복사하고, LoRA가 바꾼 가중치만 (원본 + 차이)로 다시 Q8_0 양자화해 같은 자리에 덮어쓴다.
  나머지 텐서·메타데이터는 그대로라 llama.cpp(Ollaya)가 원본과 똑같이 읽는다.
- Ollaya에는 원본 winnow 매니페스트를 복사해 가중치·decision 레이어만 바꾼 새 이름(예: winnow-vesta:r1)으로 등록한다.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

import gguf
import mlx.core as mx
import numpy as np

HF_TO_GGUF = {
    "self_attn.q_proj": "attn_q",
    "self_attn.k_proj": "attn_k",
    "self_attn.v_proj": "attn_v",
    "self_attn.o_proj": "attn_output",
    "mlp.gate_proj": "ffn_gate",
    "mlp.up_proj": "ffn_up",
    "mlp.down_proj": "ffn_down",
}


def deltas(adapter: Path) -> dict[str, np.ndarray]:
    """GGUF 텐서 이름 → 더할 차이 (출력, 입력)"""
    config = json.loads((adapter / "adapter_config.json").read_text())
    scale = config["lora_parameters"]["scale"]
    weights = mx.load(str(adapter / "adapters.safetensors"))
    out = {}
    for name, a in weights.items():
        if not name.endswith(".lora_a"):
            continue
        prefix = name[: -len(".lora_a")]                     # model.layers.3.self_attn.q_proj
        parts = prefix.split(".")
        layer = int(parts[parts.index("layers") + 1])
        key = ".".join(parts[parts.index("layers") + 2:])     # self_attn.q_proj
        b = weights[prefix + ".lora_b"]
        delta = (scale * (a.astype(mx.float32) @ b.astype(mx.float32))).T   # [in, r] @ [r, out] → (out, in)
        out[f"blk.{layer}.{HF_TO_GGUF[key]}.weight"] = np.array(delta)
    return out


def merge(base: Path, adapter: Path, target: Path) -> None:
    changes = deltas(adapter)
    reader = gguf.GGUFReader(str(base))
    tensors = {t.name: t for t in reader.tensors}
    missing = [n for n in changes if n not in tensors]
    assert not missing, missing
    shutil.copyfile(base, target)
    with open(target, "r+b") as f:
        for name, delta in changes.items():
            t = tensors[name]
            assert t.tensor_type == gguf.GGMLQuantizationType.Q8_0, (name, t.tensor_type)
            weight = gguf.quants.dequantize(t.data, t.tensor_type).astype(np.float32)
            assert weight.shape == delta.shape, (name, weight.shape, delta.shape)
            packed = gguf.quants.quantize(weight + delta, t.tensor_type)
            assert packed.nbytes == t.data.nbytes, name
            f.seek(t.data_offset)
            f.write(packed.tobytes())
    print(json.dumps({"event": "merged", "tensors": len(changes), "path": str(target)}))


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 24), b""):
            h.update(chunk)
    return h.hexdigest()


def register(gguf_path: Path, name: str, source: str = "winnow/e4b", home: Path = Path.home() / ".ollaya/models") -> None:
    """원본 매니페스트를 복사해 가중치·decision 레이어만 바꾼다. name 예: winnow-vesta:r1"""
    model, tag = name.split(":")
    manifest = json.loads((home / "manifests/ollaya.dev/library" / source).read_text())
    digest = sha256(gguf_path)
    size = gguf_path.stat().st_size
    blob = home / "blobs" / f"sha256-{digest}"
    if not blob.exists():
        shutil.copyfile(gguf_path, blob)
    layers = []
    for layer in manifest["layers"]:
        layer = dict(layer)
        if layer["mediaType"] == "application/vnd.ollaya.weights.gguf":
            layer.update(digest=f"sha256:{digest}", size=size)
            layer.pop("urls", None)
        elif layer["mediaType"] == "application/vnd.ollaya.decision":
            decision = json.loads((home / "blobs" / layer["digest"].replace(":", "-")).read_text())
            decision["gguf"].update(sha256=digest, size=size, repo="local", path=gguf_path.name)
            decision["gguf"].pop("url", None)
            data = json.dumps(decision, indent=1).encode()
            d = hashlib.sha256(data).hexdigest()
            (home / "blobs" / f"sha256-{d}").write_bytes(data)
            layer.update(digest=f"sha256:{d}", size=len(data))
            layer.pop("urls", None)
        layers.append(layer)
    manifest["layers"] = layers
    target = home / "manifests/ollaya.dev/library" / model / tag
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(manifest, indent=2))
    print(json.dumps({"event": "registered", "model": name, "sha256": digest}))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True, help="원본 Winnow GGUF (Q8_0)")
    p.add_argument("--adapter", required=True)
    p.add_argument("--out", required=True, help="합친 GGUF 경로")
    p.add_argument("--name", help="Ollaya에 등록할 이름 (예: winnow-vesta:r1)")
    args = p.parse_args()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    merge(Path(args.base), Path(args.adapter), out)
    if args.name:
        register(out, args.name)


if __name__ == "__main__":
    main()
