#!/bin/sh
# Vesta M0 인프라 설치 (PROJECT.md 2.4 로컬·무료 원칙). 여러 번 실행해도 안전하다.
#   1. Homebrew로 Ollama·Colima·Docker CLI·docker-compose·zstd·ffmpeg 설치
#   2. Colima(컨테이너 VM, 메모리 2GB) 시작·자동 시작 등록
#   3. Ollama 서비스 시작 (brew services)
#   4. Ollaya 설치 (~/.local, sudo 없음) + launchd 등록
#   5. SearXNG 컨테이너 시작
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
step() { printf '\n==> %s\n' "$*"; }

step "Homebrew 패키지"
brew install ollama colima docker docker-compose zstd ffmpeg

# brew로 설치한 docker-compose를 `docker compose` 플러그인으로 쓰기 위한 설정
DOCKER_CONFIG_FILE="$HOME/.docker/config.json"
if [ ! -f "$DOCKER_CONFIG_FILE" ]; then
    mkdir -p "$HOME/.docker"
    printf '{\n  "cliPluginsExtraDirs": ["%s/lib/docker/cli-plugins"]\n}\n' "$(brew --prefix)" >"$DOCKER_CONFIG_FILE"
elif ! grep -q cliPluginsExtraDirs "$DOCKER_CONFIG_FILE"; then
    echo "주의: $DOCKER_CONFIG_FILE 에 cliPluginsExtraDirs가 없습니다. 직접 추가하세요: $(brew --prefix)/lib/docker/cli-plugins"
fi

step "Colima (CPU 2, 메모리 2GB, 디스크 20GB)"
if ! colima status >/dev/null 2>&1; then
    colima start --cpu 2 --memory 2 --disk 20 --vm-type vz
fi
brew services start colima >/dev/null || true

step "Ollama 서비스"
brew services start ollama >/dev/null || true

step "Ollaya"
if [ ! -x "$HOME/.local/bin/ollaya" ]; then
    INSTALLER=$(mktemp -d)/install.sh
    curl -fsSL https://ollaya.dev/install.sh -o "$INSTALLER"
    OLLAYA_INSTALL_DIR="$HOME/.local" sh "$INSTALLER"
fi
"$ROOT/scripts/install-launchd.sh"

step "SearXNG"
ENV_FILE="$ROOT/infra/searxng/.env"
if [ ! -f "$ENV_FILE" ]; then
    printf 'SEARXNG_SECRET=%s\n' "$(openssl rand -hex 32)" >"$ENV_FILE"
    chmod 600 "$ENV_FILE"
fi
docker compose -f "$ROOT/infra/searxng/compose.yaml" up -d

step "완료. 상태 확인: ./gradlew :app-cli:installDist && app-cli/build/install/vesta/bin/vesta status"
