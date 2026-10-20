"""
Vesta 미디어 워커 (PROJECT.md 7.11): mlx-whisper 음성 인식을 127.0.0.1 HTTP로 제공한다.

- POST /stt?language=ko  본문: 16kHz 모노 16비트 WAV (앱이 ffmpeg로 바꿔 보낸다) → {"text", "language", "seconds"}
- GET  /health           → {"ok", "stt": {"model", "loaded", "bytes"}}
- POST /unload           → 모델을 메모리에서 내린다

모델은 처음 요청 때 올리고, --idle 초 동안 안 쓰면 내린다 (메모리 예산, PROJECT.md 2.3).

이 Mac 안에서만 받고(127.0.0.1), 모든 요청에 토큰(X-Vesta-Token)이 있어야 한다.
토큰은 --token-file(기본 ~/.vesta/media-token, 권한 600)에 있고 없으면 만든다. Vesta 서버가 같은 파일을 읽는다.
브라우저에서 온 요청(Origin 헤더)과 다른 Host 이름(DNS 리바인딩)은 받지 않는다.
"""
import argparse
import hmac
import io
import json
import logging
import os
import secrets
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import mlx.core as mx
import mlx_whisper
import numpy as np
from huggingface_hub import snapshot_download
from mlx_whisper.transcribe import ModelHolder

log = logging.getLogger("vesta-media")

MAX_BODY = 50 * 1024 * 1024
LANGUAGES = {"ko", "en", "ja", "zh", "auto"}


class Stt:
    def __init__(self, model: str, idle: int):
        self.model = model
        self.idle = idle
        self.lock = threading.Lock()
        self.last_used = 0.0
        self.loaded = False
        self.path = None

    def local_path(self) -> str:
        """받아 둔 모델이면 로컬 경로를 쓴다 (요청마다 Hugging Face에 묻지 않게). 없으면 한 번 받는다"""
        if self.path is None:
            try:
                self.path = snapshot_download(self.model, local_files_only=True)
            except Exception:
                log.info("downloading %s", self.model)
                self.path = snapshot_download(self.model)
        return self.path

    def transcribe(self, audio: np.ndarray, language: str) -> dict:
        with self.lock:
            started = time.time()
            result = mlx_whisper.transcribe(
                audio,
                path_or_hf_repo=self.local_path(),
                language=None if language == "auto" else language,
                # 무음·잡음에서 같은 말을 되풀이하는 환각을 줄인다
                condition_on_previous_text=False,
            )
            self.loaded = True
            self.last_used = time.time()
            log.info("stt %.1fs audio in %.2fs", len(audio) / 16000, time.time() - started)
            return {"text": result.get("text", "").strip(), "language": result.get("language"), "seconds": round(len(audio) / 16000, 2)}

    def unload(self):
        with self.lock:
            if not self.loaded:
                return
            ModelHolder.model = None
            ModelHolder.model_path = None
            mx.clear_cache()
            self.loaded = False
            log.info("stt model unloaded")

    def bytes(self) -> int:
        return mx.get_active_memory() if self.loaded else 0

    def reaper(self):
        while True:
            time.sleep(30)
            if self.loaded and time.time() - self.last_used > self.idle:
                self.unload()


def read_wav(data: bytes) -> np.ndarray:
    with wave.open(io.BytesIO(data)) as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2 or w.getframerate() != 16000:
            raise ValueError("16kHz mono 16-bit WAV only")
        frames = w.readframes(w.getnframes())
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


def load_token(path: str) -> str:
    """토큰 파일을 읽는다. 없으면 본인만 읽을 수 있게(600) 만든다"""
    path = os.path.expanduser(path)
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(secrets.token_urlsafe(32))
    os.chmod(path, 0o600)
    with open(path) as f:
        token = f.read().strip()
    if len(token) < 32:
        raise SystemExit(f"token too short: {path}")
    return token


def handler(stt: Stt, token: str, port: int):
    hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            log.debug(fmt, *args)

        def allowed(self) -> bool:
            """토큰·Host·Origin 확인. 실패하면 응답을 보내고 False"""
            if self.headers.get("Origin") is not None or self.headers.get("Host") not in hosts:
                self.reply(403, {"error": "forbidden"})
                return False
            if not hmac.compare_digest(self.headers.get("X-Vesta-Token", "").encode(), token.encode()):
                self.reply(401, {"error": "token required"})
                return False
            return True

        def reply(self, code: int, obj: dict):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if not self.allowed():
                return
            if urlparse(self.path).path == "/health":
                return self.reply(200, {"ok": True, "stt": {"model": stt.model, "loaded": stt.loaded, "bytes": stt.bytes()}})
            self.reply(404, {"error": "not found"})

        def do_POST(self):
            if not self.allowed():
                return
            url = urlparse(self.path)
            if url.path == "/unload":
                stt.unload()
                return self.reply(200, {"ok": True})
            if url.path != "/stt":
                return self.reply(404, {"error": "not found"})
            if self.headers.get("Content-Type", "").split(";")[0].strip() != "audio/wav":
                return self.reply(415, {"error": "audio/wav only"})
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY:
                return self.reply(413, {"error": "audio too large or empty"})
            language = parse_qs(url.query).get("language", ["ko"])[0]
            if language not in LANGUAGES:
                return self.reply(400, {"error": "unsupported language"})
            try:
                audio = read_wav(self.rfile.read(length))
            except Exception as e:  # 형식이 틀린 파일
                return self.reply(400, {"error": f"bad audio: {e}"})
            try:
                self.reply(200, stt.transcribe(audio, language))
            except Exception as e:
                log.exception("stt failed")
                self.reply(500, {"error": str(e)})

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=11436)
    parser.add_argument("--stt-model", default="mlx-community/whisper-large-v3-turbo")
    parser.add_argument("--idle", type=int, default=1800, help="이 초 동안 안 쓰면 모델을 내린다")
    parser.add_argument("--token-file", default="~/.vesta/media-token")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    stt = Stt(args.stt_model, args.idle)
    threading.Thread(target=stt.reaper, daemon=True).start()
    token = load_token(args.token_file)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(stt, token, args.port))
    log.info("Vesta media worker on 127.0.0.1:%d (stt %s)", args.port, args.stt_model)
    server.serve_forever()


if __name__ == "__main__":
    main()
