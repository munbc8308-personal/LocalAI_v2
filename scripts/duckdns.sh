#!/bin/sh
# 지인 공개 직접 모드의 무료 DDNS: DuckDNS (PROJECT.md 7.20, M26).
# iptime DDNS(*.iptime.org)는 CAA 레코드가 iptime 계정만 인증서를 받게 막아 HTTPS를 쓸 수 없다. DuckDNS는 제한이 없다.
# 공유기는 DuckDNS를 갱신하지 못하므로 이 Mac이 5분마다 공인 IP를 알린다 (launchd dev.vesta.duckdns).
# 토큰은 키체인 dev.vesta.duckdns / api-token. 프로세스 인자(ps)에 보이지 않게 표준 입력으로만 넘긴다.
#
# 사용: scripts/duckdns.sh munbc8308     토큰 저장(처음 한 번 묻는다)·갱신 서비스 등록
#       scripts/duckdns.sh off           갱신 서비스 끄기 (토큰은 남김. 지우기: security delete-generic-password -s dev.vesta.duckdns -a api-token)
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LABEL=dev.vesta.duckdns
DOMAIN="gui/$(id -u)"
BIN="$HOME/.vesta/bin"

if [ "${1:-}" = "off" ]; then
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
    echo "DuckDNS 갱신을 껐습니다."
    exit 0
fi

NAME=${1:-}
NAME=${NAME%.duckdns.org}
case "$NAME" in
    ''|*[!a-z0-9-]*) echo "사용: $0 <이름>   (이름.duckdns.org 의 이름, 또는 off)" >&2; exit 1 ;;
esac

if ! /usr/bin/security find-generic-password -s dev.vesta.duckdns -a api-token -w >/dev/null 2>&1; then
    [ -t 0 ] || { echo "토큰을 물어야 해서 터미널에서 실행하세요" >&2; exit 1; }
    printf "DuckDNS 토큰 (duckdns.org 로그인 화면 위쪽 token, 입력이 보이지 않습니다): "
    stty -echo; read -r TOKEN; stty echo; echo
    case "$TOKEN" in
        ''|*[!a-zA-Z0-9-]*) echo "토큰 형식이 아닙니다" >&2; exit 1 ;;
    esac
    printf 'add-generic-password -U -s dev.vesta.duckdns -a api-token -w %s\n' "$TOKEN" | /usr/bin/security -i >/dev/null
    unset TOKEN
    echo "키체인에 저장했습니다 (dev.vesta.duckdns)"
fi

mkdir -p "$BIN" "$HOME/.vesta/logs"
cat >"$BIN/duckdns-update.sh" <<UPD
#!/bin/sh
# scripts/duckdns.sh 가 만든 파일. launchd가 5분마다 실행한다. ip를 비우면 DuckDNS가 요청한 공인 IP로 맞춘다
TOKEN=\$(/usr/bin/security find-generic-password -s dev.vesta.duckdns -a api-token -w 2>/dev/null) || { echo "\$(date '+%F %T') 토큰 없음"; exit 1; }
# 토큰이 든 주소는 curl 설정으로 표준 입력에 넘긴다 (ps에 보이지 않게)
RESULT=\$(printf 'url = "https://www.duckdns.org/update?domains=$NAME&token=%s&ip="\n' "\$TOKEN" | /usr/bin/curl -s --max-time 20 -K -)
[ "\$RESULT" = "OK" ] || { echo "\$(date '+%F %T') 갱신 실패: \$RESULT"; exit 1; }
UPD
chmod 700 "$BIN/duckdns-update.sh"
"$BIN/duckdns-update.sh" || { echo "DuckDNS 갱신이 실패했습니다 (토큰·이름 확인. 토큰을 다시 넣으려면 security delete-generic-password -s dev.vesta.duckdns -a api-token 후 다시)" >&2; exit 1; }
echo "갱신 확인: $NAME.duckdns.org"
"$ROOT/scripts/install-launchd.sh" "$LABEL"
