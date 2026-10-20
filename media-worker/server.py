"""
Vesta 미디어 워커 (PROJECT.md 7.11): mlx-whisper 음성 인식을 127.0.0.1 HTTP로 제공한다.

- POST /stt?language=ko  본문: 16kHz 모노 16비트 WAV (앱이 ffmpeg로 바꿔 보낸다) → {"text", "language", "seconds"}
- GET  /health           → {"ok", "stt": {"model", "loaded", "bytes"}}
- POST /unload           → 모델을 메모리에서 내린다

모델은 처음 요청 때 올리고, --idle 초 동안 안 쓰면 내린다 (메모리 예산, PROJECT.md 2.3).
이 Mac 안에서만 받는다 (127.0.0.1).
"""
import argparse
import io
import json
import logging
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


def handler(stt: Stt):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            log.debug(fmt, *args)

        def reply(self, code: int, obj: dict):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if urlparse(self.path).path == "/health":
                return self.reply(200, {"ok": True, "stt": {"model": stt.model, "loaded": stt.loaded, "bytes": stt.bytes()}})
            self.reply(404, {"error": "not found"})

        def do_POST(self):
            url = urlparse(self.path)
            if url.path == "/unload":
                stt.unload()
                return self.reply(200, {"ok": True})
            if url.path != "/stt":
                return self.reply(404, {"error": "not found"})
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
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    stt = Stt(args.stt_model, args.idle)
    threading.Thread(target=stt.reaper, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(stt))
    log.info("Vesta media worker on 127.0.0.1:%d (stt %s)", args.port, args.stt_model)
    server.serve_forever()


if __name__ == "__main__":
    main()
