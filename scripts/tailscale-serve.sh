#!/bin/sh
# 휴대폰 등 다른 기기에서 웹 UI 접속 (PROJECT.md 6.3, M9).
# Vesta 서버는 127.0.0.1에만 열어 두고, Tailscale serve가 내 tailnet 안에서만 HTTPS로 받아 넘긴다.
# 공유기 포트 개방이나 Funnel(인터넷 공개)은 쓰지 않는다.
#
# 준비: Tailscale 설치·로그인 (Mac App Store 또는 https://tailscale.com/download), 휴대폰에도 같은 계정으로 로그인
#       이 Mac 터미널에서 `vesta passwd` 로 웹 비밀번호 설정
# 사용: scripts/tailscale-serve.sh        켜기 (재부팅 후에도 유지)
#       scripts/tailscale-serve.sh off    끄기
set -eu
PORT=${VESTA_PORT:-8787}

if command -v tailscale >/dev/null 2>&1; then
  TS=tailscale
elif [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
  TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
else
  echo "Tailscale이 없습니다. https://tailscale.com/download 에서 설치하고 로그인하세요." >&2
  exit 1
fi

if [ "${1:-}" = "off" ]; then
  "$TS" serve --https=443 off
  echo "Tailscale serve를 껐습니다."
  exit 0
fi

curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null || { echo "Vesta 서버가 응답하지 않습니다 (vesta status 확인)" >&2; exit 1; }
"$TS" serve --bg --https=443 "http://127.0.0.1:$PORT"
NAME=$("$TS" status --json | /usr/bin/python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')
echo "휴대폰 브라우저에서 열기: https://$NAME/"
echo "(같은 tailnet에 로그인한 기기에서만 열립니다)"
