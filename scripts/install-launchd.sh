#!/bin/sh
# infra/launchd/*.plist를 ~/Library/LaunchAgents에 설치하고 시작한다 (PROJECT.md 6.2, I-02).
# 제거: scripts/install-launchd.sh --uninstall
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
AGENTS="$HOME/Library/LaunchAgents"
DOMAIN="gui/$(id -u)"
mkdir -p "$AGENTS" "$HOME/.vesta/logs"

for template in "$ROOT"/infra/launchd/*.plist; do
    label=$(basename "$template" .plist)
    target="$AGENTS/$label.plist"
    launchctl bootout "$DOMAIN/$label" 2>/dev/null || true
    if [ "${1:-}" = "--uninstall" ]; then
        rm -f "$target"
        echo "제거: $label"
        continue
    fi
    sed "s|__HOME__|$HOME|g" "$template" >"$target"
    plutil -lint "$target" >/dev/null
    launchctl bootstrap "$DOMAIN" "$target"
    echo "설치·시작: $label ($target)"
done
