# Vesta

Apple Silicon Mac에서만 돌아가는 로컬 AI 비서. 모델·검색·음성·그림·영상이 모두 이 Mac 안에서 돌고, 무료 도구만 쓴다(Apple 앱 연동 Orchard만 선택·유료).
설계와 진행 기록은 [PROJECT.md](PROJECT.md).

## 설치

Apple Silicon(M 시리즈) Mac, 메모리 16GB 이상(32GB 이상 권장), 디스크 여유 60GB 이상.

```sh
curl -fsSL https://raw.githubusercontent.com/munbc8308-personal/LocalAI_v2/main/install.sh | sh
```

여러 번 실행해도 안전하고, 업데이트도 같은 명령이다. 하는 일:
Xcode 명령줄 도구·Homebrew·JDK 21 → 저장소(`~/.vesta/src`) → Ollama·Ollaya·ffmpeg·uv·SearXNG → 이 Mac 메모리에 맞는 모델로 설정 → 모델 받기 → 빌드·서비스 등록 → 첫 설정 → 점검.

| 메모리 | 메인 모델 |
|---|---|
| 96GB 이상 | `gemma4:26b-mxfp8` (영상 클립도 가능) |
| 64GB | `gemma4:26b-nvfp4` |
| 48GB | `gemma4:12b-mxfp8` |
| 32GB | `gemma4:12b-nvfp4` |
| 16~24GB | `gemma4:e4b-mxfp8` |

## 쓰기

```sh
vesta                # 대화 (이 폴더를 작업 폴더로)
vesta -p "질문"      # 한 번만
vesta setup          # 웹 비밀번호·메신저·Tailscale·Orchard 설정
vesta doctor         # 점검 (고치는 명령을 알려 준다)
vesta models pull    # 설정의 모델 받기
vesta update         # 새 버전으로 (설정·데이터 그대로)
vesta export         # 다른 Mac으로 옮길 파일 (새 Mac에서 vesta import 파일)
vesta uninstall      # 지우기 (데이터는 남길지 묻는다)
```

웹 UI: http://127.0.0.1:8787 (휴대폰은 Tailscale, `vesta setup`).
