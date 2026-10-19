#!/bin/sh
# Vesta 설치·업데이트 (PROJECT.md M8). 여러 번 실행해도 안전하다.
#   1. CLI·서버 빌드 (./gradlew installDist)
#   2. ~/.vesta/app/{cli,server} 로 복사, ~/.local/bin/vesta 링크
#   3. launchd dev.vesta.server 등록·재시작 (Ollaya도 함께)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP="$HOME/.vesta/app"

"$ROOT/gradlew" -p "$ROOT" -q :app-cli:installDist :app-server:installDist
mkdir -p "$APP" "$HOME/.local/bin" "$HOME/.vesta/logs"
rsync -a --delete "$ROOT/app-cli/build/install/vesta/" "$APP/cli/"
rsync -a --delete "$ROOT/app-server/build/install/vesta-server/" "$APP/server/"
ln -sf "$APP/cli/bin/vesta" "$HOME/.local/bin/vesta"
echo "설치: $APP (CLI 링크 ~/.local/bin/vesta)"

"$ROOT/scripts/install-launchd.sh"
echo "서버 확인: curl -s http://127.0.0.1:8787/api/health"
