#!/bin/sh
# 지인 공개 직접 모드: 공유기 포트포워딩 + DDNS(예: iptime) + HTTPS (PROJECT.md 7.20, M26).
# Caddy가 443을 받아 Let's Encrypt 인증서로 HTTPS를 처리하고, Vesta의 바깥 입구(127.0.0.1:8788)로만 넘긴다.
# 바깥 입구는 공개 주소 Host만 받고, 토큰 API(/v1·CLI)는 막고, IP별 요청·로그인 수를 제한한다.
# Vesta 본 포트(8787)는 계속 이 Mac 안에서만 연다: 공유기에서 8787을 포워딩하지 않는다.
#
# 준비: vesta public setup --direct --hostname <주소>
#       공유기: 이 Mac IP 고정(DHCP 고정 할당), 포트포워딩 바깥 443 → 이 Mac 443, 바깥 80 → 이 Mac 80
#       (80은 인증서 발급·http→https 넘기기용. 통신사가 80을 막으면 443만으로도 발급된다)
# 사용: scripts/direct-https.sh munbc.iptime.org    켜기 (재부팅 후에도 유지, 인증서 자동 갱신)
#       scripts/direct-https.sh off                 끄기
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PORT=${VESTA_PUBLIC_PORT:-8788}
DIR="$HOME/.vesta/caddy"
LABEL=dev.vesta.caddy
DOMAIN="gui/$(id -u)"

if [ "${1:-}" = "off" ]; then
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
    echo "HTTPS(Caddy)를 껐습니다. 공유기의 443·80 포워딩도 지우세요."
    exit 0
fi

HOST=${1:-}
case "$HOST" in
    *.*) ;;
    *) echo "사용: $0 이름.iptime.org   (또는 off)" >&2; exit 1 ;;
esac

if ! command -v caddy >/dev/null 2>&1; then
    echo "Caddy 설치 (Homebrew)…"
    brew install caddy
fi

curl -s -o /dev/null --max-time 3 "http://127.0.0.1:$PORT/" || { echo "Vesta 바깥 입구(127.0.0.1:$PORT)가 닫혀 있습니다. 먼저 vesta public setup --direct --hostname $HOST" >&2; exit 1; }

mkdir -p "$DIR/data" "$DIR/config" "$HOME/.vesta/logs"
umask 077
cat >"$DIR/Caddyfile" <<CONF
# scripts/direct-https.sh 가 만든 파일 (고치면 다음 실행 때 덮어씀)
{
	admin off
}

$HOST {
	encode gzip
	header {
		Strict-Transport-Security "max-age=31536000"
		-Server
	}
	# X-Forwarded-For는 받은 값을 버리고 실제 접속 IP로 넘긴다 (Vesta가 IP별로 제한). Cloudflare 헤더는 꾸민 것이니 지운다
	reverse_proxy 127.0.0.1:$PORT {
		header_up -Cf-Connecting-IP
		header_up -Cf-Access-Jwt-Assertion
		flush_interval -1
	}
	log {
		output file $HOME/.vesta/logs/caddy-access.log {
			roll_size 10mb
			roll_keep 5
		}
	}
}
CONF
caddy validate --config "$DIR/Caddyfile" --adapter caddyfile >/dev/null 2>&1 || { caddy validate --config "$DIR/Caddyfile" --adapter caddyfile; exit 1; }

"$ROOT/scripts/install-launchd.sh" "$LABEL"
echo ""
echo "HTTPS를 켰습니다: https://$HOST → 127.0.0.1:$PORT"
echo "처음에는 인증서 발급에 30초쯤 걸립니다 (공유기 443·80 포워딩이 되어 있어야 함). 로그: ~/.vesta/logs/caddy.err.log"
echo "확인: vesta public status   · 휴대폰 LTE에서 https://$HOST"
