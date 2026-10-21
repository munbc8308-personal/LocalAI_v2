#!/bin/sh
# Vesta 설치·업데이트 (PROJECT.md M8). 여러 번 실행해도 안전하다.
#   1. CLI·서버 빌드 (./gradlew installDist)
#   2. ~/.vesta/app/{cli,server} 로 복사, ~/.local/bin/vesta 링크
#   3. 미디어 워커(음성 인식)를 ~/.vesta/media-worker에 복사하고 uv로 Python 환경을 만든다 (M12)
#   4. 학습기(결정 모델 미세조정)를 ~/.vesta/trainer에 복사하고 uv로 Python 환경을 만든다 (M14)
#   5. launchd dev.vesta.server 등록·재시작 (Ollaya·미디어 워커도 함께)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP="$HOME/.vesta/app"

"$ROOT/gradlew" -p "$ROOT" -q :app-cli:installDist :app-server:installDist
mkdir -p "$APP" "$HOME/.local/bin" "$HOME/.vesta/logs"
rsync -a --delete "$ROOT/app-cli/build/install/vesta/" "$APP/cli/"
rsync -a --delete "$ROOT/app-server/build/install/vesta-server/" "$APP/server/"
ln -sf "$APP/cli/bin/vesta" "$HOME/.local/bin/vesta"
echo "설치: $APP (CLI 링크 ~/.local/bin/vesta)"

WORKER="$HOME/.vesta/media-worker"
UV=$(command -v uv || echo /opt/homebrew/bin/uv)
if [ -x "$UV" ]; then
    mkdir -p "$WORKER"
    rsync -a --delete --exclude .venv --exclude __pycache__ "$ROOT/media-worker/" "$WORKER/"
    # uv.lock 그대로 (같은 버전), Python 3.12는 uv가 받는다
    "$UV" sync --quiet --frozen --project "$WORKER"
    echo "미디어 워커: $WORKER (음성 인식 모델은 처음 쓸 때 받는다, 약 1.6GB)"
else
    echo "uv가 없어 미디어 워커(음성 인식)를 건너뜁니다: brew install uv 후 다시 실행" >&2
fi

TRAINER="$HOME/.vesta/trainer"
if [ -x "$UV" ]; then
    mkdir -p "$TRAINER"
    rsync -a --delete --exclude .venv --exclude __pycache__ "$ROOT/trainer/" "$TRAINER/"
    "$UV" sync --quiet --frozen --project "$TRAINER"
    echo "학습기: $TRAINER (vesta train decision)"
fi

"$ROOT/scripts/install-launchd.sh"
echo "서버 확인: curl -s http://127.0.0.1:8787/api/health"
