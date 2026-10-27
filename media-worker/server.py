"""
Vesta 미디어 워커 (PROJECT.md 7.11): mlx-whisper 음성 인식과 mflux 이미지 생성을 127.0.0.1 HTTP로 제공한다.

- POST /stt?language=ko  본문: 16kHz 모노 16비트 WAV (앱이 ffmpeg로 바꿔 보낸다) → {"text", "language", "seconds"}
- POST /image            본문: {"prompt", "width", "height", "steps", "seed"} → image/png (메타데이터에 "AI 생성")
                         모델이 아직 없으면 받기·양자화를 시작하고 503 {"error": "preparing"}
- GET  /health           → {"ok", "bytes", "stt": {...}, "image": {...}}
- POST /unload           → 모델을 메모리에서 내린다 (?model=stt|image, 없으면 둘 다)

모델은 처음 요청 때 올리고, 안 쓰면 내린다 (음성 --idle, 이미지 --image-idle 초. 메모리 예산, PROJECT.md 2.3).
이미지 모델은 공식 FLUX.1-schnell(Hugging Face 로그인 필요)을 한 번 받아 4bit로 양자화해 --image-dir에 저장하고, 이후에는 그것만 읽는다.

이 Mac 안에서만 받고(127.0.0.1), 모든 요청에 토큰(X-Vesta-Token)이 있어야 한다.
토큰은 --token-file(기본 ~/.vesta/media-token, 권한 600)에 있고 없으면 만든다. Vesta 서버가 같은 파일을 읽는다.
브라우저에서 온 요청(Origin 헤더)과 다른 Host 이름(DNS 리바인딩)은 받지 않는다.
"""
import argparse
import gc
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

# 큰 파일은 일반 HTTP로 받는다: xet 전송은 수 GB 파일에서 "error decoding response body"로 끊기고 이어받지 못했다 (M13)
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")

import mlx.core as mx
import mlx_whisper
import numpy as np
from huggingface_hub import snapshot_download
from mlx_whisper.transcribe import ModelHolder
from PIL.PngImagePlugin import PngInfo

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


class Tts:
    """Qwen3-TTS(mlx-audio) 한국어 읽어 주기 (M37). 처음 부를 때 올리고, 오래 안 쓰면 내린다. 한 번에 하나만"""

    SPEAKERS = {"serena", "vivian", "uncle_fu", "ryan", "aiden", "ono_anna", "sohee", "eric", "dylan"}

    def __init__(self, model: str, idle: int):
        self.model_name = model
        self.idle = idle
        self.lock = threading.Lock()
        self.last_used = 0.0
        self.model = None

    def synthesize(self, text: str, speaker: str, language: str) -> bytes:
        import io, wave
        from mlx_audio.tts.utils import load_model
        with self.lock:
            if self.model is None:
                self.model = load_model(self.model_name)
                log.info("tts model loaded: %s", self.model_name)
            self.last_used = time.time()
            chunks = [np.array(r.audio) for r in self.model.generate_custom_voice(text=text, speaker=speaker, language=language)]
            sr = self.model.sample_rate
        audio = np.clip(np.concatenate(chunks), -1.0, 1.0) if chunks else np.zeros(1, dtype=np.float32)
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(sr)
            w.writeframes((audio * 32767).astype("<i2").tobytes())
        return buf.getvalue()

    def unload(self):
        with self.lock:
            if self.model is None:
                return
            self.model = None
            mx.clear_cache()
            log.info("tts model unloaded")

    def reaper(self):
        while True:
            time.sleep(30)
            if self.model is not None and time.time() - self.last_used > self.idle:
                self.unload()


class Imager:
    """FLUX.1-schnell 이미지 생성. 한 번에 하나만 그린다"""

    def __init__(self, directory: str, idle: int, bits: int):
        self.directory = os.path.expanduser(directory)
        self.idle = idle
        self.bits = bits
        self.lock = threading.Lock()
        self.flux = None
        self.last_used = 0.0
        self.preparing = False
        self.error = None

    @property
    def ready(self) -> bool:
        # 저장이 끝나야 이름을 바꾸므로(.tmp → 최종) 폴더에 가중치가 있으면 다 받은 것
        return os.path.isdir(self.directory) and any(f.endswith(".safetensors") for _, _, files in os.walk(self.directory) for f in files)

    def prepare_async(self):
        """처음 한 번: 공식 가중치를 받아 양자화해 저장한다 (오래 걸린다, 약 33GB 받기)"""
        with self.lock:
            if self.preparing or self.ready:
                return
            self.preparing = True
            self.error = None
        threading.Thread(target=self._prepare, daemon=True).start()

    def _prepare(self):
        try:
            from mflux.models.common.config import ModelConfig
            from mflux.models.common.resolution.model_revisions import ModelRevisions
            from mflux.models.flux.variants.txt2img.flux import Flux1
            from mflux.models.flux.weights.flux_weight_definition import FluxWeightDefinition
            log.info("image model: downloading and quantizing FLUX.1-schnell to %d bit", self.bits)
            # 끊겨도 받은 데까지 이어서 받는다
            for attempt in range(1, 6):
                try:
                    # mflux가 읽는 파일·버전만 (저장소의 단일 파일 flux1-schnell.safetensors 등은 받지 않는다)
                    repo = ModelConfig.schnell().model_name
                    snapshot_download(repo, allow_patterns=FluxWeightDefinition.get_download_patterns(), revision=ModelRevisions.get(repo))
                    break
                except Exception as e:
                    if attempt == 5:
                        raise
                    log.warning("download failed (%d/5), retrying: %s", attempt, e)
                    time.sleep(10 * attempt)
            with self.lock:
                flux = Flux1(model_config=ModelConfig.schnell(), quantize=self.bits)
                tmp = self.directory + ".tmp"
                flux.save_model(tmp)
                os.replace(tmp, self.directory)
                del flux
                gc.collect()
                mx.clear_cache()
            log.info("image model saved: %s", self.directory)
        except Exception as e:
            log.exception("image model prepare failed")
            self.error = str(e)
        finally:
            self.preparing = False

    def generate(self, prompt: str, width: int, height: int, steps: int, seed: int) -> bytes:
        from mflux.models.common.config import ModelConfig
        from mflux.models.flux.variants.txt2img.flux import Flux1
        with self.lock:
            # 그리는 중에 유휴 정리가 내리지 않게 시작할 때도 기록한다
            self.last_used = time.time()
            if self.flux is None:
                started = time.time()
                self.flux = Flux1(model_config=ModelConfig.schnell(), model_path=self.directory)
                log.info("image model loaded in %.1fs", time.time() - started)
            started = time.time()
            result = self.flux.generate_image(seed=seed, prompt=prompt, num_inference_steps=steps, width=width, height=height)
            self.last_used = time.time()
            log.info("image %dx%d %d steps in %.1fs", width, height, steps, time.time() - started)
            info = PngInfo()
            info.add_text("Software", "Vesta")
            info.add_text("Comment", "AI 생성 (Vesta, FLUX.1-schnell)")
            info.add_text("ai_generated", "true")
            info.add_text("prompt", prompt)
            info.add_text("seed", str(seed))
            out = io.BytesIO()
            result.image.save(out, format="PNG", pnginfo=info)
            return out.getvalue()

    def unload(self, only_if_idle: bool = False):
        with self.lock:
            if self.flux is None:
                return
            # 잠금을 기다리는 동안 그렸을 수 있으므로 다시 확인한다
            if only_if_idle and time.time() - self.last_used <= self.idle:
                return
            self.flux = None
            gc.collect()
            mx.clear_cache()
            log.info("image model unloaded")

    def reaper(self):
        while True:
            time.sleep(30)
            if self.flux is not None and time.time() - self.last_used > self.idle:
                self.unload(only_if_idle=True)


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


def handler(stt: Stt, imager: Imager, token: str, port: int, tts: "Tts"):
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
                return self.reply(200, {
                    "ok": True,
                    # 이 워커가 GPU에 올린 메모리 전체 (음성·이미지 모델)
                    "bytes": mx.get_active_memory(),
                    "stt": {"model": stt.model, "loaded": stt.loaded, "bytes": stt.bytes()},
                    "image": {"model": "FLUX.1-schnell", "ready": imager.ready, "loaded": imager.flux is not None,
                              "preparing": imager.preparing, "error": imager.error},
                })
            self.reply(404, {"error": "not found"})

        def do_POST(self):
            if not self.allowed():
                return
            url = urlparse(self.path)
            if url.path == "/unload":
                which = parse_qs(url.query).get("model", ["all"])[0]
                if which in ("all", "stt"):
                    stt.unload()
                if which in ("all", "image"):
                    imager.unload()
                if which in ("all", "tts"):
                    tts.unload()
                return self.reply(200, {"ok": True})
            if url.path == "/tts":
                return self.speech()
            if url.path == "/image":
                return self.image()
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

        def speech(self):
            """{"text","speaker","language"} → audio/wav (M37)"""
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > 64_000:
                return self.reply(413, {"error": "text too large or empty"})
            try:
                req = json.loads(self.rfile.read(length))
                text = str(req["text"]).strip()[:2000]
                speaker = str(req.get("speaker") or "sohee")
                language = str(req.get("language") or "Korean")
            except Exception as e:
                return self.reply(400, {"error": f"bad request: {e}"})
            if not text or speaker not in Tts.SPEAKERS or language not in ("Korean", "English", "Japanese", "Chinese", "auto"):
                return self.reply(400, {"error": "bad text, speaker or language"})
            try:
                body = tts.synthesize(text, speaker, language)
            except Exception as e:
                log.exception("tts failed")
                return self.reply(500, {"error": str(e)})
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def image(self):
            if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
                return self.reply(415, {"error": "application/json only"})
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > 64 * 1024:
                return self.reply(413, {"error": "bad request size"})
            try:
                body = json.loads(self.rfile.read(length))
                prompt = str(body["prompt"]).strip()
                width = int(body.get("width", 1024))
                height = int(body.get("height", 1024))
                steps = int(body.get("steps", 4))
                seed = int(body.get("seed", secrets.randbelow(2**31)))
            except Exception:
                return self.reply(400, {"error": "bad request"})
            if not prompt or len(prompt) > 2000 or not (1 <= steps <= 8) or \
                    any(v < 256 or v > 1536 or v % 16 for v in (width, height)):
                return self.reply(400, {"error": "prompt 1~2000 chars, size 256~1536 (multiple of 16), steps 1~8"})
            if not imager.ready:
                # 지난번 실패 이유를 알려 주고 다시 시도한다
                previous = imager.error
                imager.prepare_async()
                return self.reply(503, {"error": "preparing", "detail": previous})
            try:
                png = imager.generate(prompt, width, height, steps, seed)
            except Exception as e:
                log.exception("image failed")
                return self.reply(500, {"error": str(e)})
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(png)))
            self.send_header("X-Seed", str(seed))
            self.end_headers()
            self.wfile.write(png)

    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=11436)
    parser.add_argument("--stt-model", default="mlx-community/whisper-large-v3-turbo")
    parser.add_argument("--idle", type=int, default=1800, help="이 초 동안 안 쓰면 모델을 내린다")
    parser.add_argument("--token-file", default="~/.vesta/media-token")
    parser.add_argument("--image-dir", default="~/.vesta/models/flux1-schnell-4bit", help="양자화해 저장한 이미지 모델")
    parser.add_argument("--image-idle", type=int, default=600, help="이 초 동안 안 그리면 이미지 모델을 내린다")
    parser.add_argument("--image-bits", type=int, default=4)
    parser.add_argument("--tts-model", default="mlx-community/Qwen3-TTS-12Hz-1.7B-CustomVoice-bf16")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    stt = Stt(args.stt_model, args.idle)
    threading.Thread(target=stt.reaper, daemon=True).start()
    imager = Imager(args.image_dir, args.image_idle, args.image_bits)
    threading.Thread(target=imager.reaper, daemon=True).start()
    token = load_token(args.token_file)
    tts = Tts(args.tts_model, args.idle)
    threading.Thread(target=tts.reaper, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(stt, imager, token, args.port, tts))
    log.info("Vesta media worker on 127.0.0.1:%d (stt %s)", args.port, args.stt_model)
    server.serve_forever()


if __name__ == "__main__":
    main()
