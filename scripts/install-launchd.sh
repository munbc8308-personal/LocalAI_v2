#!/bin/sh
# infra/launchd/*.plist를 ~/Library/LaunchAgents에 설치하고 시작한다 (PROJECT.md 6.2, I-02).
# 제거: scripts/install-launchd.sh --uninstall
# 선택 서비스(infra/launchd/optional/, 예: 리랭커)는 이름을 주면 설치한다: scripts/install-launchd.sh dev.vesta.reranker
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
AGENTS="$HOME/Library/LaunchAgents"
DOMAIN="gui/$(id -u)"
mkdir -p "$AGENTS" "$HOME/.vesta/logs"

TEMPLATES="$ROOT/infra/launchd/*.plist"
case "${1:-}" in
    dev.vesta.*) TEMPLATES="$ROOT/infra/launchd/optional/$1.plist" ;;
esac
for template in $TEMPLATES; do
    label=$(basename "$template" .plist)
    # 미디어 워커는 Python 환경이 있을 때만 (install-vesta.sh가 만든다)
    if [ "$label" = "dev.vesta.media" ] && [ "${1:-}" != "--uninstall" ] && [ ! -x "$HOME/.vesta/media-worker/.venv/bin/python" ]; then
        echo "건너뜀: $label (미디어 워커 환경 없음)"
        continue
    fi
    target="$AGENTS/$label.plist"
    launchctl bootout "$DOMAIN/$label" 2>/dev/null || true
    # bootout은 비동기라, 완전히 내려간 뒤에 다시 올려야 bootstrap이 실패하지 않는다 (오류 5)
    i=0
    while launchctl print "$DOMAIN/$label" >/dev/null 2>&1 && [ $i -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
    if [ "${1:-}" = "--uninstall" ]; then
        rm -f "$target"
        echo "제거: $label"
        continue
    fi
    JAVA_HOME_DIR=${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}
    sed -e "s|__HOME__|$HOME|g" -e "s|__JAVA_HOME__|$JAVA_HOME_DIR|g" "$template" >"$target"
    plutil -lint "$target" >/dev/null
    n=0
    until launchctl bootstrap "$DOMAIN" "$target" 2>/dev/null; do
        n=$((n + 1))
        [ $n -ge 5 ] && { echo "등록 실패: $label (launchctl bootstrap $DOMAIN $target)" >&2; exit 1; }
        sleep 1
    done
    echo "설치·시작: $label ($target)"
done
