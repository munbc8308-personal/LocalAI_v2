"""Vesta가 부르는 클립 생성 (PROJECT.md G-03): mlx-video Wan2 generate를 MLX 버퍼 캐시 한도를 걸고 돌린다.
한도가 없으면 캐시가 100GB 가까이 남아(실제 최대 사용은 약 40GB) 메인 모델 자리까지 차지한다. 끝나면 실제 최대 사용량을 한 줄로 낸다"""
import json
import os
import runpy
import sys

import mlx.core as mx

mx.set_cache_limit(int(os.environ.get("VESTA_CLIP_CACHE_GB", "4")) * 1024**3)
sys.argv = ["generate"] + sys.argv[1:]
try:
    runpy.run_module("mlx_video.models.wan_2.generate", run_name="__main__")
finally:
    print(json.dumps({"event": "memory", "peak_gb": round(mx.get_peak_memory() / 1e9, 1)}), flush=True)
