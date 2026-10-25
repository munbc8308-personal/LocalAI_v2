#!/bin/zsh
# 셸 격리 실행 이미지 만들기 (PROJECT.md 7.25, M31). Colima(Docker)가 떠 있어야 한다.
#   scripts/shell-sandbox.sh        이미지 vesta-shell 만들기 (다시 만들어도 안전)
#   scripts/shell-sandbox.sh check  있는지만 확인
set -eu
ROOT="${0:A:h:h}"
export DOCKER_HOST="${DOCKER_HOST:-unix://$HOME/.colima/default/docker.sock}"
if ! docker info >/dev/null 2>&1; then
  echo "Docker에 연결하지 못했습니다. 'colima start'로 켜 주세요." >&2
  exit 1
fi
if [[ "${1:-}" == "check" ]]; then
  docker image inspect vesta-shell >/dev/null 2>&1 && echo "vesta-shell 있음" || { echo "vesta-shell 없음: scripts/shell-sandbox.sh"; exit 1; }
  exit 0
fi
docker build -t vesta-shell "$ROOT/infra/shell"
echo "vesta-shell 이미지를 만들었습니다. 설정 tools.shellMode가 sandbox(기본)면 셸 명령이 이 안에서 돕니다."
