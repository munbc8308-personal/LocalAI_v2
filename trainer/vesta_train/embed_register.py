"""학습한 임베딩 GGUF를 Ollama 모델로 등록한다 (PROJECT.md L-23): 파일을 blob으로 올리고 /api/create"""
import argparse
import hashlib
import json
import urllib.request


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--gguf", required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--ollama", default="http://127.0.0.1:11434")
    args = p.parse_args()
    digest = hashlib.sha256()
    with open(args.gguf, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    blob = "sha256:" + digest.hexdigest()
    with open(args.gguf, "rb") as f:
        request = urllib.request.Request(f"{args.ollama}/api/blobs/{blob}", data=f, method="POST", headers={"Content-Length": str(__import__("os").path.getsize(args.gguf))})
        urllib.request.urlopen(request, timeout=600).read()
    body = json.dumps({"model": args.name, "files": {"model.gguf": blob}, "stream": False}).encode()
    result = json.load(urllib.request.urlopen(urllib.request.Request(f"{args.ollama}/api/create", body, {"Content-Type": "application/json"}), timeout=600))
    if result.get("status") != "success":
        raise SystemExit(f"Ollama 등록 실패: {result}")
    print(json.dumps({"event": "registered", "name": args.name, "blob": blob}), flush=True)


if __name__ == "__main__":
    main()
