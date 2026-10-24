#!/bin/sh
# Vesta 한 줄 설치 (PROJECT.md N-01, M23). Apple Silicon Mac 전용. 여러 번 실행해도 안전하다 (업데이트도 같은 명령).
#
#   curl -fsSL https://raw.githubusercontent.com/munbc8308-personal/LocalAI_v2/main/install.sh | sh
#
# 하는 일 (이미 된 것은 건너뛴다):
#   1. 확인: macOS·Apple Silicon, 디스크
#   2. Xcode 명령줄 도구 (git·swiftc), Homebrew, JDK 21
#   3. 저장소 받기·갱신: ~/.vesta/src (VESTA_SRC로 바꿀 수 있다)
#   4. 인프라: Ollama·Ollaya·ffmpeg·uv·Colima·SearXNG (scripts/setup-macos.sh)
#   5. 설정: 이 Mac 메모리에 맞는 모델로 ~/.vesta/config.yaml (있으면 그대로)
#   6. 모델 받기 (진행률, 디스크 확인)
#   7. 빌드·서비스 등록 (scripts/install-vesta.sh)
#   8. 첫 설정 (웹 비밀번호·메신저·Tailscale·Orchard, 터미널이 있을 때) → 점검 (vesta doctor)
set -eu

REPO=${VESTA_REPO:-https://github.com/munbc8308-personal/LocalAI_v2.git}
BRANCH=${VESTA_BRANCH:-main}
SRC=${VESTA_SRC:-$HOME/.vesta/src}
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
# curl | sh 로 실행하면 표준 입력이 이 스크립트라, 묻는 것은 터미널(/dev/tty)에서 받는다
has_tty() { [ -r /dev/tty ] && [ -w /dev/tty ] && (exec </dev/tty) 2>/dev/null; }

step "확인"
[ "$(uname -s)" = "Darwin" ] || fail "macOS에서만 설치할 수 있습니다."
[ "$(uname -m)" = "arm64" ] || fail "Apple Silicon(M 시리즈) Mac에서만 설치할 수 있습니다 (MLX·Ollama MLX 엔진)."
MEM_GB=$(( $(sysctl -n hw.memsize) / 1073741824 ))
FREE_GB=$(df -g "$HOME" | awk 'NR==2 {print $4}')
echo "메모리 ${MEM_GB}GB, 디스크 남은 공간 ${FREE_GB}GB"
[ "$MEM_GB" -ge 15 ] || echo "주의: 메모리 16GB 미만은 권하지 않습니다."
[ "$FREE_GB" -ge 60 ] || fail "디스크 여유가 60GB 이상 필요합니다 (모델 30~50GB + 빌드·환경)."

step "Xcode 명령줄 도구"
if ! xcode-select -p >/dev/null 2>&1; then
    xcode-select --install || true
    echo "설치 창이 뜨면 '설치'를 누르세요. 끝날 때까지 기다립니다…"
    until xcode-select -p >/dev/null 2>&1; do sleep 10; done
fi
echo "있음: $(xcode-select -p)"

step "Homebrew"
if [ ! -x /opt/homebrew/bin/brew ]; then
    has_tty || fail "Homebrew 설치에는 비밀번호 입력이 필요합니다. 터미널에서 직접 실행하세요."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" </dev/tty
fi
eval "$(/opt/homebrew/bin/brew shellenv)"
echo "있음: $(brew --version | head -1)"

step "JDK 21"
if ! /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    brew list openjdk@21 >/dev/null 2>&1 || brew install openjdk@21
    # 시스템 폴더에 링크하려면 sudo가 필요해서, 링크 대신 JAVA_HOME으로 쓴다 (launchd 설정에도 이 값이 들어간다)
    JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
else
    JAVA_HOME=$(/usr/libexec/java_home -v 21)
fi
export JAVA_HOME
echo "JAVA_HOME=$JAVA_HOME"

step "저장소: $SRC"
if [ -d "$SRC/.git" ]; then
    if git -C "$SRC" diff --quiet && git -C "$SRC" diff --cached --quiet; then
        git -C "$SRC" fetch --quiet origin "$BRANCH" && git -C "$SRC" checkout --quiet "$BRANCH" && git -C "$SRC" merge --quiet --ff-only "origin/$BRANCH" \
            || echo "주의: 갱신하지 못했습니다 (지금 것으로 계속)."
    else
        echo "주의: $SRC 에 고친 파일이 있어 갱신하지 않습니다 (지금 것으로 계속)."
    fi
else
    mkdir -p "$(dirname "$SRC")"
    git clone --quiet --branch "$BRANCH" "$REPO" "$SRC"
fi
echo "버전: $(git -C "$SRC" log -1 --format='%h %s')"

step "인프라 (Ollama·Ollaya·ffmpeg·uv·Colima·SearXNG)"
"$SRC/scripts/setup-macos.sh"

step "설정 (이 Mac에 맞는 모델)"
"$SRC/gradlew" -p "$SRC" -q :app-cli:installDist
VESTA="$SRC/app-cli/build/install/vesta/bin/vesta"
"$VESTA" config init --keep

step "모델 받기"
# Ollama가 막 켜졌으면 잠깐 기다린다
i=0; until curl -sf http://127.0.0.1:11434/api/version >/dev/null 2>&1 || [ $i -ge 30 ]; do sleep 1; i=$((i + 1)); done
"$VESTA" models pull

step "빌드·서비스 등록"
VESTA_SRC="$SRC" "$SRC/scripts/install-vesta.sh"
VESTA="$HOME/.local/bin/vesta"
i=0; until curl -sf http://127.0.0.1:8787/api/health >/dev/null 2>&1 || [ $i -ge 60 ]; do sleep 1; i=$((i + 1)); done

if has_tty; then
    step "첫 설정"
    VESTA_SRC="$SRC" "$VESTA" setup </dev/tty || true
else
    echo "터미널이 없어 첫 설정을 건너뜁니다. 나중에: vesta setup"
fi

step "점검"
"$VESTA" doctor || true
case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) printf '\n~/.local/bin 을 PATH에 넣으세요: echo '"'"'export PATH="$HOME/.local/bin:$PATH"'"'"' >> ~/.zshrc\n' ;;
esac
printf '\n끝. 대화: vesta · 웹: http://127.0.0.1:8787 · 다시 설치·업데이트: 같은 명령\n'
