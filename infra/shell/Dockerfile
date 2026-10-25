# 셸 격리 실행 이미지 (PROJECT.md 7.25 X-02, M31). Vesta의 run_shell이 이 안에서 돈다: 네트워크 없음, 작업 폴더만 붙임.
# 기본 이미지는 다이제스트로 고정한다 (scripts/shell-sandbox.sh가 만든다)
FROM debian:bookworm-slim@sha256:7ff6ba31527b9e336162fa061746c366c4b2032f4d2cff2078bea43f78efe3f8

RUN apt-get update \
 && apt-get install -y --no-install-recommends bash zsh git python3 make ripgrep jq file zip unzip less tzdata ca-certificates coreutils findutils grep sed gawk tree \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8 HOME=/tmp
LABEL dev.vesta.role="shell-sandbox"
