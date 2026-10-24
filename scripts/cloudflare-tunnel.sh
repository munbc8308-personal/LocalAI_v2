#!/bin/sh
# 초대한 지인에게 공개: Cloudflare Tunnel (PROJECT.md 7.20 P-01, M26).
# 이 Mac에서 Cloudflare로 나가는 연결만 쓴다 (공유기 포트 개방 없음). 터널은 Vesta의 공개 입구(127.0.0.1:8788)로만 넘긴다:
# 그 입구는 Cloudflare Access 토큰이 없으면 막고, 토큰 API(/v1·CLI)는 받지 않는다.
#
# 준비: Cloudflare 계정에 내 도메인(무료 요금제 가능), vesta public setup --hostname <주소> (Access 로그인 관문)
# 사용: scripts/cloudflare-tunnel.sh vesta.example.com    만들기·켜기 (재부팅 후에도 유지)
#       scripts/cloudflare-tunnel.sh off                  끄기 (터널·DNS는 Cloudflare에 남김)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NAME=${VESTA_TUNNEL:-vesta}
PORT=${VESTA_PUBLIC_PORT:-8788}
CONF_DIR="$HOME/.vesta/cloudflared"
LABEL=dev.vesta.cloudflared
DOMAIN="gui/$(id -u)"

if [ "${1:-}" = "off" ]; then
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
    echo "터널을 껐습니다. 다시 켜기: $0 <주소>"
    exit 0
fi

HOST=${1:-}
case "$HOST" in
    *.*) ;;
    *) echo "사용: $0 vesta.example.com   (또는 off)" >&2; exit 1 ;;
esac

if ! command -v cloudflared >/dev/null 2>&1; then
    echo "cloudflared 설치 (Homebrew)…"
    brew install cloudflared
fi
CF=$(command -v cloudflared)

# 계정 연결: 브라우저가 열리면 이 주소의 도메인을 고른다 (~/.cloudflared/cert.pem)
if [ ! -f "$HOME/.cloudflared/cert.pem" ]; then
    echo "브라우저에서 Cloudflare에 로그인하고 도메인(${HOST#*.})을 고르세요…"
    "$CF" tunnel login
fi

# 지운 터널은 목록에 나오지 않는다
tunnel_id() {
    "$CF" tunnel list --output json 2>/dev/null | /usr/bin/python3 -c 'import json,sys
print(next((t["id"] for t in json.load(sys.stdin) or [] if t.get("name")==sys.argv[1]), ""))' "$NAME" || true
}
ID=$(tunnel_id)
if [ -z "$ID" ]; then
    echo "터널 만들기: $NAME"
    "$CF" tunnel create "$NAME" >/dev/null
    ID=$(tunnel_id)
    [ -n "$ID" ] || { echo "터널을 만들지 못했습니다" >&2; exit 1; }
fi
CRED="$HOME/.cloudflared/$ID.json"
[ -f "$CRED" ] || { echo "터널 자격 파일이 없습니다: $CRED (다른 Mac에서 만든 터널이면 VESTA_TUNNEL=다른이름 으로 새로 만드세요)" >&2; exit 1; }
chmod 600 "$CRED"

# 주소 → 터널 (DNS CNAME). 이미 있으면 덮어쓴다
"$CF" tunnel route dns --overwrite-dns "$NAME" "$HOST"

mkdir -p "$CONF_DIR" "$HOME/.vesta/logs"
umask 077
cat >"$CONF_DIR/config.yml" <<CONF
# scripts/cloudflare-tunnel.sh 가 만든 파일 (고치면 다음 실행 때 덮어씀)
tunnel: $ID
credentials-file: $CRED
ingress:
  - hostname: $HOST
    service: http://127.0.0.1:$PORT
  - service: http_status:404
CONF
"$CF" tunnel --config "$CONF_DIR/config.yml" ingress validate >/dev/null

"$ROOT/scripts/install-launchd.sh" "$LABEL"
echo ""
echo "터널을 켰습니다: https://$HOST → 127.0.0.1:$PORT"
echo "확인: vesta public status   (몇 초 걸릴 수 있음)"
