# 영상 클립 환경 (M24 G-03)

mlx-video(MIT, 고정 커밋)로 장면 그림을 몇 초짜리 영상으로 움직인다. `scripts/install-vesta.sh`가 `~/.vesta/video`에 복사하고 `uv sync`한다.
모델은 직접 받고 변환한다(용량이 커서 자동으로 받지 않는다):

```bash
cd ~/.vesta/video
uv run python -c "from huggingface_hub import snapshot_download; snapshot_download('Wan-AI/Wan2.2-TI2V-5B', local_dir='models/Wan2.2-TI2V-5B')"
uv run python -m mlx_video.models.wan_2.convert --checkpoint-dir models/Wan2.2-TI2V-5B --output-dir models/Wan2.2-TI2V-5B-MLX
```

그다음 `~/.vesta/config.yaml`에 `media: { clips: {} }`를 넣으면 VideoAgent가 움직이는 장면을 만든다(PROJECT.md 13.32).
