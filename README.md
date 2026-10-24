# Vesta

Apple Silicon Mac에서만 돌아가는 로컬 AI 비서. 모델·검색·음성·그림·영상이 모두 이 Mac 안에서 돌고, 무료 도구만 쓴다(Apple 앱 연동 Orchard만 선택·유료).
설계와 진행 기록은 [PROJECT.md](PROJECT.md).

## 설치

Apple Silicon(M 시리즈) Mac, 메모리 16GB 이상(32GB 이상 권장), 디스크 여유 60GB 이상.

```sh
curl -fsSL https://raw.githubusercontent.com/munbc8308-personal/LocalAI_v2/main/install.sh | sh
```

여러 번 실행해도 안전하고, 업데이트도 같은 명령이다. 하는 일:
Xcode 명령줄 도구·Homebrew·JDK 21 → 저장소(`~/.vesta/src`) → Ollama·Ollaya·ffmpeg·uv·SearXNG → 이 Mac 메모리에 맞는 모델로 설정 → 모델 받기 → 빌드·서비스 등록 → 첫 설정 → 점검.

| 메모리 | 메인 모델 |
|---|---|
| 96GB 이상 | `gemma4:26b-mxfp8` (영상 클립도 가능) |
| 64GB | `gemma4:26b-nvfp4` |
| 48GB | `gemma4:12b-mxfp8` |
| 32GB | `gemma4:12b-nvfp4` |
| 16~24GB | `gemma4:e4b-mxfp8` |

## 쓰기

```sh
vesta                # 대화 (이 폴더를 작업 폴더로)
vesta -p "질문"      # 한 번만
vesta setup          # 웹 비밀번호·메신저·Tailscale·Orchard 설정
vesta doctor         # 점검 (고치는 명령을 알려 준다)
vesta models pull    # 설정의 모델 받기
vesta update         # 새 버전으로 (설정·데이터 그대로)
vesta export         # 다른 Mac으로 옮길 파일 (새 Mac에서 vesta import 파일)
vesta uninstall      # 지우기 (데이터는 남길지 묻는다)
vesta memory list    # 나에 대해 기억하는 것 (대화에서 "기억해: …", /forget, /memory)
vesta schedule list  # 예약 작업 (대화에서 /schedule 매일 8시 오늘 날씨 알려 줘, /schedules)
```

대화에서 도구로 여러 번 일해 끝낸 작업은 쉬는 때 "제안된 스킬"(초안)이 됩니다. `/skillify`는 방금 한 일로 바로 만들고, `/skills`로 보고 `/skills approve 이름`(또는 웹 UI 에이전트 탭)으로 승인해야 `/이름 할 일`로 부를 수 있습니다.

지난 대화: "지난번에 얘기한 …"처럼 물으면 예전 대화를 찾아 참고하고, `/recall 검색어`나 웹 UI 대화 목록의 검색 칸으로 찾을 수 있습니다.

예약 작업: `/schedule 매일 8시 오늘 날씨와 일정 알려 줘`처럼 만들면 그 시각에 새 대화로 돌고, 메신저에서 만들었으면 결과가 그 대화방으로 옵니다. 지켜보는 사람이 없으므로 쓰기·셸 같은 승인 단계는 하지 않습니다.

웹 UI: http://127.0.0.1:8787 (휴대폰은 Tailscale, `vesta setup`).

## 지인에게 공개 (선택)

초대한 사람만 주소 하나로 접속합니다. 방법은 두 가지입니다.

### 공유기 + DDNS (DuckDNS, 무료)

iptime DDNS(`*.iptime.org`)는 인증서 발급이 막혀 있어(CAA) HTTPS를 쓸 수 없습니다. duckdns.org에서 이름을 만듭니다.

```sh
scripts/duckdns.sh 이름                       # 토큰(키체인)·5분마다 공인 IP 갱신
vesta public setup --direct --hostname 이름.duckdns.org
scripts/direct-https.sh 이름.duckdns.org      # HTTPS (Caddy, Let's Encrypt)
vesta public invite mina                     # 초대 (기본 손님)
vesta public status
```

공유기에서 이 Mac의 IP를 고정하고, 바깥 443 → 이 Mac 443, 바깥 80 → 이 Mac 80을 포워딩합니다. 8787은 포워딩하지 않습니다.
바깥 로그인은 12자 이상 비밀번호만 됩니다.

### Cloudflare (공유기 설정 없음)

필요한 것: Cloudflare 계정에 올린 내 도메인, Zero Trust 팀(무료).

```sh
vesta public token                                              # Cloudflare API 토큰 (키체인)
vesta public setup --hostname vesta.example.com --email 내@메일  # Access 로그인 관문
scripts/cloudflare-tunnel.sh vesta.example.com                  # 터널
vesta public invite mina mina@example.com                       # 초대 (기본 손님: 대화·웹 검색)
vesta public status                                             # 바깥에서 막고 있는지 확인
```

Cloudflare 방식은 Access 로그인과 Vesta 로그인을 모두 거칩니다. 두 로그인은 같은 사람이어야 합니다(이메일).
토큰 API(`/v1`·CLI)는 바깥에 열지 않습니다. 관리 기능은 이 Mac에서만 씁니다.
하루 질문 한도는 `~/.vesta/config.yaml`의 `users.dailyQuestions`로 정합니다.
