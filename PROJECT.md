# Vesta — 프로젝트 기획서

> 상태: M0 진행 중 (v1.6, 2026-10-17)
> 구현 전 단계. "결정 필요" 항목은 확정 후 갱신한다.

### 변경 이력
| 버전 | 날짜 | 내용 |
|------|------|------|
| v0.1 | 2026-10-15 | 최초 초안 |
| v0.2 | 2026-10-15 | 4대 용도(대화/문서/코드/에이전트) 전부 지원으로 확정, 하드웨어(128GB, 70% 예산) 반영, 추론 엔진을 MLX 기반으로 변경 |
| v0.3 | 2026-10-15 | 인터넷 검색 추가, RAG·웹 검색 기반 답변 품질 파이프라인 정의, 결정 모델(Router) 도입으로 "모드" 수동 선택을 "에이전트 자동 선택"으로 변경 |
| v0.4 | 2026-10-15 | 검색 백엔드 SearXNG(무료)로 확정, 웹 검색 사용 여부는 결정 모델이 판단, 무료 결정 모델 후보 조사 반영, 임베딩 유사도 라우팅 단계 추가 |
| v0.5 | 2026-10-15 | 결정 모델을 Jev 방식(typed decision)으로 재정의: Laya + Ollaya 채택, Jev 제외(유료 호스팅), GLM-5.3-Flash는 로컬 제외·외부 승격 옵션. 전용 라우터 LLM·임베딩 유사도 단계 제거, 결정 모델 활용처(D1~D10) 정의 |
| v0.6 | 2026-10-15 | 외부 승격(GLM-5.3-Flash API) 제거 — 완전 로컬·무료 확정. Laya 한국어 성능 부족 시 `decider`로 전환 확정 |
| v0.7 | 2026-10-15 | 칩 M5 확정(GPU Neural Accelerator 활용), Docker 설치 확정(SearXNG), 코드 모델 분리 확정 |
| v0.8 | 2026-10-15 | Gradle 멀티 모듈 확정(의존 규칙 정의), 셸 실행 도구 포함 확정(매번 승인 후 실행) |
| v0.9 | 2026-10-15 | 전체 점검 반영(오래된 router 역할 제거, ConversationService 추가, RoutingDecision 정합성, 프라이버시 문구). 채널 추가: 웹 UI, Telegram(1순위), 기타 메신저 조사. 상시 실행 서버 구조로 전환, Compose Desktop 보류 |
| v1.0 | 2026-10-16 | Kotlin 적극 사용 원칙(웹 UI도 Kotlin: Compose Multiplatform Web, 공유 API 모델 KMP 모듈), 메신저 답변에 로컬 정보 허용, 메신저 셸 승인 허용, 2순위 메신저 Discord·Slack 확정. 남은 질문 없음 |
| v1.1 | 2026-10-16 | 시스템 이름 **Vesta** 확정 (CLI 명령어, 패키지, 설정 경로, 봇·서비스 이름 반영) |
| v1.2 | 2026-10-16 | Apple 앱 연동 추가: 로컬에 설치된 **Orchard**(MCP 서버, 52개 도구)를 MCP 클라이언트로 연동. `PersonalAgent`·D11(앱 그룹 선택) 추가, MCP 도구 위험 등급 매핑, `mcp` 모듈, 마일스톤 M6a |
| v1.3 | 2026-10-16 | **로컬·무료 인프라 원칙** 추가(2.4): 구성 요소별 라이선스·비용 표, Docker Desktop 대신 Colima, LM Studio 제외, 오픈 라이선스 모델만 사용, Orchard는 무료 기능만 기본(Pro 앱은 무료 자체 도구로 대체 가능), JDK는 무료 OpenJDK 배포판 |
| v1.4 | 2026-10-16 | **Orchard Pro 구매 허용** — 무료 원칙의 유일한 예외. Pro 앱(Mail·Notes·Messages·Contacts·Music·Maps·Weather·Shortcuts)을 P1로 올리고, A-09 무료 대체 도구는 보류(Orchard 장애·중단 시 대비책)로 내림 |
| v1.5 | 2026-10-16 | 비목표였던 **모델 학습(LoRA)·이미지·음성·멀티 유저**를 범위에 포함 (7.10~7.12). 비목표는 클라우드 배포 등으로 축소. `ImageAgent`, 모델 역할 IMAGE·VISION·STT·TTS, `training`·`media`·`identity` 모듈, 마일스톤 M12~M15 추가. 데이터 모델 전 테이블에 `userId` |
| v1.6 | 2026-10-17 | **M0 착수**: Gradle 멀티 모듈 골격, 인프라 설치(Ollama 0.40.5, Ollaya 0.13.0, Colima, SearXNG), `vesta status`·`bench` 명령. 13.1 M0 진행 기록 추가. Laya 한국어 제로샷 첫 측정 |

---

## 1. 개요

### 1.1 한 줄 정의
Kotlin(JVM)으로 만드는, **Apple Silicon Mac에서 동작하는 로컬 우선(Local-first) 개인 AI 어시스턴트 시스템**.
추론과 데이터 저장은 모두 로컬에서 하고, 최신 정보가 필요할 때만 인터넷 검색을 사용한다.

### 1.1a 이름 — Vesta
로마 신화에서 화로와 가정을 지키는 여신이다. 집 Mac에서 늘 켜져 있고, 데이터를 밖으로 내보내지 않는 개인 AI라는 성격을 나타낸다.

| 용도 | 값 |
|------|-----|
| CLI 명령어 | `vesta` |
| Gradle 루트 프로젝트 | `rootProject.name = "vesta"` |
| Kotlin 패키지 | `dev.vesta.*` (예: `dev.vesta.core`, `dev.vesta.agents`) |
| 설정·데이터 경로 | `~/.vesta/` (`config.yaml`, DB, 인덱스, 로그) |
| launchd 라벨 | `dev.vesta.server` |
| 메신저 봇 이름 | Telegram `@<계정>_vesta_bot` 형식, Discord·Slack 앱 이름 `Vesta` |
| 키체인 서비스 이름 | `dev.vesta` |

로컬 저장소 폴더 이름(`LocalAI/`)은 그대로 두어도 된다. 빌드 산출물과 패키지 이름은 위 표를 따른다.

### 1.2 배경 / 동기
- 클라우드 LLM은 데이터가 외부로 나가고, 비용이 계속 발생한다.
- Apple Silicon의 통합 메모리와 MLX 덕분에 대형 로컬 모델도 실사용 가능한 속도가 나온다.
- 로컬 모델은 지식이 학습 시점에 멈춰 있고 내 문서를 모른다. → **RAG + 인터넷 검색**으로 보완한다.
- 용도마다 적합한 처리 방식이 다르다. → **결정 모델(텍스트를 생성하지 않고 판정만 하는 모델)이 질문을 보고 알맞은 에이전트와 정보원을 고른다.**

### 1.3 목표
| 용도 | 목표 |
|------|------|
| **일반 대화** | 로컬 모델과 스트리밍 대화, 대화 기록 유지 |
| **문서 질의 (RAG)** | 등록한 문서 기반으로 출처와 함께 답변 |
| **웹 리서치** | 인터넷 검색 결과를 읽고 출처와 함께 최신 정보로 답변 |
| **코드 도우미** | 로컬 코드베이스 이해, 설명, 리뷰, 수정안(diff) 제시 |
| **작업 에이전트** | 도구를 스스로 호출해 여러 단계 작업 수행 (위험 작업은 승인) |
| **음성 · 이미지** | 음성으로 묻고 음성으로 듣기(STT·TTS), 사진을 보여 주고 질문, 이미지 생성 — 모두 로컬 모델 |
| **맞춤 학습** | 쓰면서 쌓인 피드백으로 결정 모델·생성 모델을 LoRA로 로컬 파인튜닝, 평가를 통과한 것만 반영 |
| **멀티 유저** | 가족·소규모 팀이 한 Mac을 같이 쓰되, 대화·문서·권한은 사용자별로 분리 |
| **개인 비서 (Apple 앱)** | 캘린더·미리 알림·메일·메모·메시지·연락처 등 Mac의 Apple 앱을 Orchard(MCP)로 조회·조작 (쓰기·발송은 승인) |
| **자동 라우팅** | 사용자가 모드를 고르지 않아도 결정 모델이 적절한 에이전트와 정보원을 선택 |
| **멀티 채널** | CLI, 웹 UI, 텔레그램 등 메신저 어디서든 같은 어시스턴트 사용 |

### 1.4 기술 원칙 — Kotlin 우선
- 앱 코드는 **가능한 한 전부 Kotlin**으로 작성한다: 서버, CLI, 채널 어댑터, **웹 UI까지**.
- 서버와 웹 UI가 주고받는 데이터 모델은 Kotlin Multiplatform 공유 모듈(`api-model`) 하나로 정의해 양쪽에서 같이 쓴다.
- 라이브러리는 Kotlin 네이티브(코루틴 기반)를 우선 선택하고, 없을 때만 Java 라이브러리를 쓴다.
- Kotlin이 아닌 것은 외부 프로세스(Ollama, Ollaya, SearXNG, Orchard, 미디어·학습 워커)뿐이다. Ollama·Ollaya·SearXNG·미디어 워커는 HTTP로, Orchard는 MCP(stdio)로 연동한다. 학습은 Kotlin이 Python 스크립트를 작업(job)으로 실행·감시한다 (7.10).

### 1.5 비목표
- **클라우드 배포·호스팅** — 로컬·무료 원칙(2.4)과 충돌. 서버는 이 Mac에서만 돈다.
- **대규모 사용자** — 멀티 유저는 가족·소규모 팀(최대 5명 정도)까지. 공개 서비스, 회원 가입, 결제는 하지 않는다.
- **모델 사전학습·전체 파인튜닝** — 학습은 LoRA/QLoRA 어댑터와 소형 결정 모델 미세조정까지 (7.10).
- **영상 생성, 음성 복제(남의 목소리)** — 메모리·윤리 문제로 제외.
- **완전한 실시간 음성 대화(전이중)** — 음성은 "녹음 → 변환 → 답변 → 읽어 주기" 반이중 방식까지 (7.11).

(v1.5에서 모델 학습, 이미지·음성, 멀티 유저는 비목표에서 빠지고 기능 범위에 들어갔다.)

---

## 2. 사용 환경

### 2.1 대상
- 사용자: 개발자 본인(소유자) + 가족·소규모 팀 구성원 (멀티 유저, 7.12). 최대 5명 정도
- 하드웨어: **Apple M5 Mac, 통합 메모리 128GB**
- 컨테이너: Docker 호환 런타임 **Colima** (무료 오픈소스, SearXNG 실행용. 2.4 참고)
- 운영 형태: 상시 실행 서버 (6.2 참고)
- OS: macOS

### 2.2 메모리 예산 — 전체의 70%
- 총 128GB × 70% = **약 89.6GB → 운영 상한 89GB**로 둔다.
- 나머지 약 39GB는 macOS, IDE, 브라우저 등 다른 작업용으로 남긴다.
- 예산은 **모델 가중치 + KV 캐시 + 앱(JVM) + 벡터 인덱스 + 보조 서비스**를 모두 포함한다.

| 항목 | 예상 사용량 | 로드 정책 |
|------|------------|-----------|
| 메인 모델 (대화·문서·웹·작업) | ~40GB | 상시 |
| 코드 모델 (분리 확정) | ~20GB | 필요 시 로드, 30분 미사용 시 언로드 |
| 결정 모델 (Laya, Ollaya 런타임) | ~1GB | 상시 (모든 요청이 거쳐 감, CPU 실행) |
| 임베딩 모델 | ~2GB | 상시 |
| 리랭커 모델 | ~2GB | 상시 |
| KV 캐시 (긴 컨텍스트) | ~12GB | 컨텍스트 길이·동시 세션 수에 따라 변동 |
| 앱 (JVM 힙 + 벡터 인덱스) | ~4GB | |
| 검색 서비스 (SearXNG 컨테이너) | ~2GB | 웹 검색 사용 시 |
| 여유분 | ~6GB | |
| **합계** | **~89GB** | |

수치는 추정치다. M0 단계에서 실제 모델로 측정해 확정한다.

**필요할 때만 올리는 추가 모델** (상시 모델과 동시에 89GB를 넘으면 LRU로 코드 모델 등을 내린다)

| 항목 | 예상 사용량 | 로드 정책 |
|------|------------|-----------|
| 이미지 생성 (FLUX.1-schnell, 4bit) | ~10GB | `/image` 요청 시, 10분 미사용 시 언로드. 코드 모델과 동시 로드 시 코드 모델을 내림 |
| 음성 인식 STT (Whisper large-v3-turbo) | ~2GB | 음성 입력 시, 30분 미사용 시 언로드 |
| 음성 합성 TTS (MeloTTS-KR) | ~1GB | 음성 답변 시. macOS `say`는 추가 메모리 없음 |
| 학습 작업 (LoRA/QLoRA) | 20~45GB | **유지보수 시간대에만**. 학습 중에는 코드·이미지 모델을 내리고, 메인 모델은 학습 대상이면 내린다 (7.10) |

### 2.3 메모리 예산 정책
- 앱이 **로드된 모델의 메모리 합계를 추적**하고, 새 모델 로드 시 89GB를 넘으면 가장 오래 안 쓴 모델(LRU)을 내린다.
- 상시 모델(메인, 결정, 임베딩, 리랭커)은 언로드 대상에서 제외한다.
- 상한값은 설정 파일에서 바꿀 수 있다 (`memory.budgetPercent: 70`).
- 참고: macOS는 GPU가 쓸 수 있는 통합 메모리에 기본 상한이 있다. 89GB 이하로 운영하면 대부분 기본값으로 충분하다. 부족하면 `sudo sysctl iogpu.wired_limit_mb=<값>`으로 조정할 수 있다 (재부팅 시 초기화).

### 2.4 인프라 원칙 — 로컬 · 무료
**기본 구성은 이 Mac 한 대에서, 돈을 내지 않고 돌아가야 한다.**
- 모든 서버·엔진·DB는 로컬 프로세스로 실행한다. 클라우드 서버, 호스팅 DB, 유료 API는 쓰지 않는다.
- 소프트웨어는 **오픈소스 또는 개인 무료**인 것만 쓴다. 유료 구독이 필요한 기능은 "선택"으로 두고, 없어도 핵심 기능은 동작해야 한다.
- **예외: Orchard Pro** (v1.4 사용자 결정). 구매해서 쓴다. Orchard가 없어도 Apple 앱 연동을 뺀 나머지 기능은 동작해야 한다.
- 모델은 **로컬 실행 가능한 오픈 가중치 + 상업·개인 사용 제한이 적은 라이선스**(Apache 2.0, MIT 우선)만 쓴다.
- 외부 서비스는 꼭 필요한 곳(웹 검색 대상 사이트, 메신저, 원격 접속)만 쓰고, 모두 **무료 플랜**이다. 이들이 없어도 CLI·로컬 웹 UI는 동작한다.
- 설치는 **Homebrew**(무료)로 통일하고, 상시 실행은 macOS 내장 **launchd**로 관리한다.

| 구성 요소 | 선택 | 라이선스 / 비용 | 실행 위치 | 비고 |
|-----------|------|-----------------|-----------|------|
| JDK | **Eclipse Temurin** 또는 OpenJDK (Gradle toolchain 자동 설치) | GPLv2+CE, 무료 | 로컬 | Oracle JDK는 쓰지 않음. 이 Mac에 OpenJDK 27 설치되어 있음 (빌드 대상은 21 LTS) |
| 빌드 | Gradle | Apache 2.0 | 로컬 | |
| 추론 엔진 | **Ollama** (MLX 엔진) | MIT | 로컬 | 대안 mlx-lm(MIT). LM Studio는 무료지만 비공개 소스라 제외 |
| 결정 모델 런타임 | Ollaya | Apache 2.0 | 로컬 (CPU) | |
| 결정 모델 | Laya | Apache 2.0 | 로컬 | `decider` 전환 시 라이선스 재확인 |
| 생성·코드·임베딩·리랭커 모델 | Qwen3.5 / Qwen3-Coder / Qwen3-Embedding·bge-m3 / Qwen3-Reranker·bge-reranker / gpt-oss | Apache 2.0 또는 MIT | 로컬 | 사용 제한 라이선스(예: Llama 커뮤니티 라이선스)는 후보에서 뺀다 |
| 인젝션 탐지 | Qwen3Guard | Apache 2.0 | 로컬 | |
| 학습 | **mlx-lm** (`mlx_lm.lora`, `mlx_lm.fuse`), PyTorch(MPS) + Hugging Face Transformers·Optimum(ONNX 내보내기) | MIT / BSD / Apache 2.0 | 로컬 | Python 환경은 **uv**(MIT)로 관리 |
| 이미지 생성 | **mflux** (MLX용 FLUX) + FLUX.1-schnell | MIT / Apache 2.0 | 로컬 | FLUX.1-dev는 비상업 라이선스라 제외 |
| 비전 입력 | 비전 지원 생성 모델 (Qwen 계열 VL 등, M0에서 확정) | Apache 2.0 | 로컬 | |
| 음성 인식 | **mlx-whisper** 또는 whisper.cpp + Whisper large-v3-turbo | MIT | 로컬 | |
| 음성 합성 | macOS `say`(한국어 음성 내장) → MeloTTS(한국어) | OS 내장 / MIT | 로컬 | |
| 오디오 변환 | ffmpeg | LGPL/GPL, 무료 | 로컬 | 메신저 음성(OGG/Opus) ↔ WAV |
| 컨테이너 런타임 | **Colima** + Docker CLI | MIT / Apache 2.0, 무료 | 로컬 VM | Docker Desktop은 조건부 무료·비공개라 쓰지 않음. VM 메모리 2GB 상한 |
| 웹 검색 | SearXNG | AGPL-3.0, 무료 | 로컬 컨테이너 | API 키 불필요. 검색 결과는 외부 검색 엔진에서 가져옴 |
| DB · 검색 인덱스 | SQLite (+ FTS5, sqlite-vec) | Public Domain / MIT·Apache | 로컬 파일 | 별도 DB 서버 없음 |
| Apple 앱 연동 | Orchard **Pro** (앱 11개 전체) | **유료 (유일한 예외)** | 로컬 | 가격은 구매 시 확인해 이 표에 기록 |
| 비밀 보관 | macOS 키체인 | OS 내장 | 로컬 | |
| 프로세스 관리 | launchd | OS 내장 | 로컬 | |
| 원격 접속 | Tailscale **Personal** | 개인 무료 (최대 3명, 100대) | 외부 조정 서버 + 기기 간 WireGuard | 선택. 안 쓰면 웹 UI는 이 Mac에서만 접속 |
| 메신저 | Telegram / Discord / Slack | 무료 (Slack 무료 플랜은 기록 90일) | 외부 서비스 | 선택. 메시지가 각 회사 서버를 거침 (6.6) |
| 앱 라이브러리 | Ktor, Exposed, Kord, Bolt, Clikt, Hoplite, PDFBox, JGit, Compose 등 | Apache 2.0 / MIT / EPL 등 오픈소스 | 앱 내부 | 의존성 추가 시 라이선스 확인 |

**비용: Orchard Pro 구독료만** (전기료 제외). 나머지 구성 요소는 모두 0원이다.

---

## 3. 추론 엔진 — Apple Silicon 전용 MLX

### 3.1 MLX란
- Apple이 Apple Silicon 전용으로 만든 머신러닝 프레임워크.
- 통합 메모리를 CPU/GPU가 복사 없이 공유하므로 Mac에서 가장 효율적이다.
- **사용 칩이 M5이므로** Ollama MLX 엔진이 GPU Neural Accelerator를 활용한다 (Ollama 발표 기준 M5 Max에서 MoE 모델 decode 약 1.9배 향상). safetensors(MLX) 포맷 모델을 우선 사용해야 이 이득을 받는다.
- 결정 모델 런타임 Ollaya는 ONNX Runtime CPU 실행이라 Neural Accelerator 혜택은 없다.

### 3.2 MLX를 쓰는 방법 (후보)
| 방식 | 설명 | 장점 | 단점 |
|------|------|------|------|
| **Ollama (MLX 엔진)** | Ollama 0.19부터 Apple Silicon에서 MLX 엔진 사용 (2026-03 프리뷰). safetensors 모델은 MLX, GGUF 모델은 기존 llama.cpp로 실행 | 설치·모델 관리 쉬움, 임베딩 API 제공, 다중 모델 로드/언로드 관리 | MLX 경로는 아직 프리뷰, 모델 포맷에 따라 엔진이 달라짐 |
| **mlx-lm server** | Apple MLX 팀의 Python 패키지. OpenAI 호환 서버 제공 | MLX 원본, 최신 모델 지원 빠름 | 다중 모델 관리, tool calling 지원 수준 확인 필요 |
| **vllm-mlx** | MLX 위의 vLLM 스타일 서버 | OpenAI API 호환, tool calling 지원 | 서드파티 프로젝트, 성숙도 확인 필요 |
| LM Studio | GUI 앱, MLX 엔진 내장 | 사용 쉬움 | 자동화·서버 운용에 덜 적합, 비공개 소스 → **제외** (2.4) |

### 3.3 결정 (초안)
- **1순위: Ollama (MLX 엔진, safetensors 모델 사용)** — 모델 관리와 임베딩까지 한 곳에서 해결.
- **대안: mlx-lm server / vllm-mlx** — Ollama MLX 경로에 문제가 있거나 최신 모델이 필요할 때.
- 앱은 **OpenAI 호환 API를 기본 프로토콜**로 쓴다. 엔진을 바꿔도 설정의 URL만 바꾸면 되게 한다.
- MLX는 Python/C++ 라이브러리라 JVM에서 직접 임베드하지 않는다. **별도 프로세스(로컬 서버) + HTTP 통신** 구조로 간다.
- 결정 모델은 MLX가 아니라 **Ollaya(ONNX Runtime)** 로 따로 실행한다 (4장 참고).
- 결정 모델 신뢰도 미달 시 메인 모델이 JSON으로 판정하므로, 엔진의 structured output 지원 여부를 M0에서 확인한다.

---

## 4. 결정 모델(Decision Model)과 에이전트

### 4.1 개념 — System 1 / System 2 분리
- **결정 모델(System 1)**: 텍스트를 생성하지 않고, 정해진 질문에 **타입이 있는 답(선택지·점수·확률)** 만 빠르게 돌려준다. 수십 ms 단위.
- **생성 모델(System 2)**: 메인 LLM. 답변 작성, 검색어 생성, 도구 호출 등 텍스트 생성이 필요한 일을 맡는다.
- 원칙: **"고르기·판정하기"는 결정 모델, "쓰기·만들기"는 생성 모델**이 한다. 판정을 생성 모델로 하면 느리고 JSON 파싱이 불안정하다.

결정 모델의 답 형식 (Jev/Laya/Ollaya 공통 규격)
| 타입 | 의미 | 반환 |
|------|------|------|
| `choice` | 여러 선택지 중 하나 고르기 | 선택지, 신뢰도, 선택지별 확률 |
| `score` | 순서 있는 등급(2~10단계) 매기기 | 기대 등급, 신뢰도, 등급별 확률 |
| `noul` | 예/아니오 명제 | 명제가 참일 확률 |

### 4.2 결정 모델 조사 결과 (2026-10 기준)
| 이름 | 정체 | 형태 / 라이선스 | 로컬 실행 | 판단 |
|------|------|-----------------|-----------|------|
| **Jev** (TypeSafe AI, 2026-09) | 결정 모델의 원조. choice/score/noul을 70~500ms에 반환 | **호스팅 API 전용, 유료**($0.042/백만 입력 토큰, 출력 무료). 가중치 비공개. Eden AI 경유 사용 가능 | 불가 | **제외** — 무료·로컬 원칙 위배, 질문 내용이 외부로 나감. 단, API 규격은 표준으로 채택 |
| **Laya** (Convai Innovations) | Jev와 같은 방식의 **오픈 가중치** 결정 모델 | Apache 2.0. `laya:en`(ModernBERT-large 421M), `laya:multilingual`(mmBERT-base 322M, 100+ 언어), `laya:typed-decisions`(영어 파인튜닝), `laya`(언어 자동 분기 라우터) | 가능 (CPU로도 빠름) | **채택** — 무료, 1GB 미만, 수십 ms |
| **Ollaya** (2026-09) | 결정 모델용 로컬 런타임. "결정 모델판 Ollama" | Apache 2.0, Rust, ONNX Runtime. `serve`/`run`/`pull`/`list` 명령. 포트 `11435`, `POST /api/decide`. TypeSafe(Jev) API 호환 | macOS 지원 (조사 당시 CPU 실행. 0.7.0부터 macOS용 MLX 엔진 포함 — Laya가 GPU를 쓰는지는 M4에서 확인) | **채택** — 결정 모델 실행 엔진 |
| **GLM-5.3-Flash** (Zhipu, 2026-08) | 결정 모델이 아니라 **범용 멀티모달 생성 LLM**. 320B MoE(활성 18B), 1M 컨텍스트, 텍스트·이미지·영상 입력 | MIT, 오픈 가중치 | 4bit 약 200GB, 1bit 약 93GB → **89GB 예산 초과** | **사용 안 함** — 메모리 예산 밖, 외부 API 사용도 하지 않음 (4.3 참고) |

Ollaya 라이브러리의 기타 모델 (M0에서 검토)
| 모델 | 내용 | 용도 후보 |
|------|------|-----------|
| `decider` | Qwen3.5 기반 약 2B 결정 모델 | **Laya 한국어 성능 부족 시 전환 대상 (확정)** |
| `winnow:e4b` | Ollaya 공식 추천. 약 8GB, typed decision 정확도 0.722 | 참고용 (현재 계획에 없음) |
| `Qwen3Guard` | 안전성 판정 모델 | 웹 페이지 프롬프트 인젝션·유해 입력 탐지 |

### 4.3 GLM-5.3-Flash 활용 방안
- 결정 모델이 아니므로 라우터 용도로는 쓰지 않는다.
- 로컬 메인 모델로 쓰기에는 너무 크다 (4bit 약 200GB > 89GB 예산).
- 외부 API로 보내는 승격(escalation)도 하지 않는다 — **완전 로컬·무료 원칙 유지** (v0.6 확정).
- 향후: GLM-5.3 계열 소형/증류 모델이 나오면 메인 또는 비전(이미지·문서 이해) 모델 후보로 재검토한다.

### 4.4 결정 모델 활용처
결정 모델은 라우팅 한 곳이 아니라 **시스템 곳곳의 "판정 지점"** 에 쓴다. 한 요청에 여러 질문을 묶어 한 번에 호출한다.

| # | 지점 | 질문 예시 | 타입 | 판정 후 동작 |
|---|------|-----------|------|--------------|
| D1 | **에이전트 선택** | 어떤 에이전트가 처리해야 하나? | `choice` (CHAT/DOCS/WEB/CODE/TASK/PERSONAL/IMAGE) | 해당 에이전트 실행 |
| D2 | **웹 검색 필요 여부** | 최신 정보·외부 사실이 필요한가? | `noul` | 확률 ≥ 임계값이면 웹 검색 |
| D3 | **로컬 문서 필요 여부** | 등록된 문서/코드를 참고해야 하나? | `noul` | 로컬 RAG 실행 |
| D4 | **컬렉션 선택** | 어느 컬렉션이 관련 있나? | `choice` (컬렉션 설명 기반, 20개 이하) | 해당 컬렉션만 검색 |
| D5 | **난이도 판정** | 질문 난이도는? | `score` (1~5) | 낮으면 짧은 생성·작은 컨텍스트, 높으면 추론 모드·더 많은 검색 |
| D6 | **검색 결과 충분성** | 수집한 근거로 답할 수 있나? | `noul` | 부족하면 검색어 보완 후 재검색 (최대 3회) |
| D7 | **근거 검증** | 이 답변 문장이 근거로 뒷받침되나? | `noul` (문장별) | 근거 없는 문장 표시 또는 재생성 |
| D8 | **도구 위험도** | 이 도구 호출은 얼마나 위험한가? | `score` (READ~EXEC) | 규칙 기반 등급과 비교, 더 높은 쪽 적용 |
| D9 | **작업 완료 여부** | 에이전트 작업이 끝났나? | `noul` | 루프 종료 또는 계속 |
| D10 | **인젝션 탐지** | 웹 페이지·메일·메시지·메모에 모델 대상 지시문이 있나? | `noul` (Qwen3Guard 등) | 해당 내용 제외 또는 경고 |
| D11 | **Apple 앱 그룹 선택** | 어느 앱의 도구가 필요한가? | `choice` (calendar/reminders/mail/notes/messages/contacts/music/maps/weather/clock/shortcuts, 다중 선택은 확률 상위 N개) | 선택된 그룹의 MCP 도구만 모델에 노출 (7.7a) |

MVP(P0)는 D1~D3. 나머지는 단계적으로 추가한다. D11은 M6a(Apple 앱 연동)에서 추가한다.

### 4.5 라우팅 흐름
```
사용자 질문
    │
    ▼
┌───────────────────────────────┐
│ 1단계: 명시적 지정             │  /code, /web, @컬렉션 → 바로 해당 에이전트
└──────────────┬────────────────┘
               ▼
┌───────────────────────────────┐
│ 2단계: 규칙 기반 판정          │  파일 경로·코드 블록 첨부, URL 포함 등 확실한 신호
└──────────────┬────────────────┘
               ▼
┌───────────────────────────────┐
│ 3단계: 결정 모델 (Laya/Ollaya) │  D1 + D2 + D3 (+ D4, D5) 를 한 번에 질의, 약 수십 ms
└──────────────┬────────────────┘
               ▼
       신뢰도 ≥ 임계값? ── 아니오 ──▶ 4단계: 메인 모델이 JSON으로 판정 (폴백)
               │ 예                        여전히 애매하면 ChatAgent + 사용자에게 선택지 제시
               ▼
     RoutingDecision 확정 → 에이전트 실행
               │
               ▼
     검색이 필요하면 QueryRewriter(메인 모델)가 검색어 생성
```

- 결정 모델은 텍스트를 만들지 못하므로 **검색어 생성은 메인 모델의 QueryRewriter**가 맡는다.
- 이로써 v0.4의 전용 라우터 LLM(Qwen3.5-4B, 약 4GB)과 임베딩 유사도 단계는 **제거**한다.

### 4.6 결정 결과 형식 (RoutingDecision)
```json
{
  "agent":      { "value": "DOCS", "confidence": 0.91 },
  "needsWeb":   0.78,
  "needsLocal": 0.95,
  "collection": { "value": "design-docs", "confidence": 0.84 },
  "difficulty": { "level": 3, "confidence": 0.70 },
  "stage": "DECISION_MODEL",
  "latencyMs": 42
}
```

### 4.7 결정 모델 요구사항 / 주의점
- **한국어 검증 필수**: Laya multilingual은 "100+ 언어"라고만 되어 있고 한국어는 명시되지 않았다. 한국어 평가셋으로 정확도를 먼저 확인한다.
- **보정(Calibration) 필수**: `laya:multilingual`은 보정 온도 없이 배포되어 신뢰도가 과신 경향이 있다. 라벨 데이터로 임계값을 직접 맞춘다.
- **언어 오판 주의**: 영어 체크포인트에 다른 언어를 넣으면 정답률 0%인데도 신뢰도 0.95가 나온 사례가 있다. 신뢰도만 믿지 말고 언어 분기(`laya` 라우터)를 반드시 거친다.
- **선택지 20개 이하**: 선택지가 많으면 정확도가 급락한다 (Banking77에서 0.425). 에이전트 7개, Apple 앱 그룹 11개(D11), 컬렉션은 상위 범주로 묶는다.
- **입력 길이**: 컨텍스트 512~1024 토큰. 긴 대화는 최근 질문 + 요약만 `state`로 넘긴다.
- **기본 체크포인트 한계**: `en`/`multilingual` 기본 체크포인트는 typed decision 제로샷 성능이 낮다(0.362). 정확도가 부족하면 4.7a 전환 규칙에 따라 `decider`로 바꾼다.
- **실행 속도**: M0 측정 결과 `laya:multilingual` 판정 1회 약 26ms(모델 로드 1.6초 별도). 지연 시간 목표(100ms) 안이다.
- **평가**: 라우팅 평가셋(한국어 질문 → 정답 에이전트/웹 필요 여부)으로 정확도 90% 이상 목표.

### 4.7a 결정 모델 전환 규칙 (Laya → decider)
1. M4에서 `laya`(한국어는 `laya:multilingual`로 분기)를 한국어 평가셋으로 측정한다.
2. 보정 후에도 **라우팅 정확도 90% 미만**이면 `decider`(Qwen3.5 기반, 약 2B)로 전환한다.
3. 전환은 설정만 바꾼다 (`decision.model: decider`). 앱 코드는 `DecisionClient` 인터페이스만 쓰므로 바뀌지 않는다.
4. `decider` 전환 시 확인할 것:
   - 메모리: 약 2B라 Laya(1GB 미만)보다 커진다 (fp16 기준 약 4~5GB 예상). 2.2 표의 여유분 안에서 수용한다.
   - 지연 시간: macOS에서 CPU 실행이므로 100ms 목표를 넘는지 측정한다. 넘으면 D1~D3만 `decider`, 나머지 판정은 `laya` 유지하는 혼합 구성을 검토한다.
5. 측정 결과는 이 문서에 기록하고 버전을 올린다.

### 4.8 에이전트 목록
| 에이전트 | 담당 | 사용 모델 | 정보원 | 도구 |
|----------|------|-----------|--------|------|
| `ChatAgent` | 일반 대화, 상식, 글쓰기 | general | 없음 | 시각, 계산기 |
| `DocsAgent` | 등록 문서 기반 질의 | general | 로컬 문서 RAG | 문서 검색 |
| `WebResearchAgent` | 최신 정보, 뉴스, 외부 사실 확인 | general | 웹 검색 | 웹 검색, 페이지 읽기 |
| `CodeAgent` | 코드 설명, 리뷰, 수정안 | code | 코드 RAG (+ 필요 시 웹 문서) | 파일 읽기, 코드 검색, git diff |
| `TaskAgent` | 여러 단계 작업, 파일 조작 | general (tool calling) | 필요 시 전부 | 전체 (승인 정책 적용) |
| `ImageAgent` | 이미지 생성 | general(프롬프트 작성) + image | 없음 | 이미지 생성 도구 |
| `PersonalAgent` | 일정·미리 알림·메일·메모·메시지·연락처 등 개인 비서 업무 | general (tool calling) | Orchard MCP 도구·리소스 | D11로 고른 앱 그룹의 Orchard 도구 (승인 정책 적용) |

정보원은 섞을 수 있다. 예: "우리 설계 문서랑 최신 Spring 권장 방식 비교해줘" → `DocsAgent` + 웹 검색 (D2·D3 모두 참).

`PersonalAgent` 예: "내일 오후 일정 비어 있어?", "어제 김 팀장 메일 요약해줘", "회의 끝나고 장보기 미리 알림 추가해줘". 파일·셸과 Apple 앱을 함께 쓰는 복합 작업(예: "이 PDF 요약해서 메일로 보내줘")은 `TaskAgent`가 Orchard 도구도 함께 사용한다.

### 4.9 모델 역할(Role) 설정
```yaml
models:
  general:   { name: "<메인 모델>",     keepAlive: forever }
  code:      { name: "<코드 모델>",     keepAlive: 30m }
  embedding: { name: "<임베딩 모델>",   keepAlive: forever }
  reranker:  { name: "<리랭커 모델>",   keepAlive: forever }
  vision:    { name: "<비전 모델>",     keepAlive: 30m }    # 메인 모델이 비전 지원이면 general과 같게
  image:     { name: "flux.1-schnell", engine: mflux, keepAlive: 10m }
  stt:       { name: "whisper-large-v3-turbo", engine: mlx-whisper, keepAlive: 30m }
  tts:       { engine: say, voice: Yuna }                # 또는 engine: melotts

decision:
  engine: ollaya
  baseUrl: http://127.0.0.1:11435
  model: laya                  # 언어 자동 분기
  thresholds:
    agent: 0.6
    needsWeb: 0.5
    needsLocal: 0.5
  fallback: general            # 신뢰도 미달 시 메인 모델로 판정
  # 한국어 성능 부족 시: model: decider

mcp:
  servers:
    orchard:
      transport: stdio
      command: /Applications/Orchard.app/Contents/MacOS/orchard-cli
      args: [mcp]
      enabled: true
      toolOverrides:             # 서버가 주는 annotations보다 우선 (7.7a 위험 등급 표)
        mail_send: EXEC
        messages_send: EXEC
        shortcuts_run: EXEC
        music_control: READ      # 재생 제어는 자동 실행
```

### 4.10 생성 모델 후보 (M0에서 벤치마크 후 확정)
| 역할 | 후보 | 선정 기준 |
|------|------|-----------|
| general | Qwen3.5-35B-A3B 계열 (MoE), gpt-oss-120b, 70B급 dense 4bit (Apache 2.0·MIT 라이선스만) | 한국어 품질, tool calling 정확도, 속도 |
| code | Qwen3-Coder 계열 (MoE) | 코드 이해/생성 품질, 긴 컨텍스트 |
| embedding | Qwen3-Embedding, bge-m3 | 한국어·코드 검색 품질 |
| reranker | Qwen3-Reranker, bge-reranker | 검색 정확도 향상 폭 |
| decision | `laya` (1순위), `decider` (한국어 부족 시) | 한국어 정확도, 보정 후 신뢰도, 지연 시간 |

MoE 모델은 MLX에서 속도 이득이 특히 크므로 우선 검토한다.

---

## 5. 답변 품질 파이프라인 (RAG + 웹 검색)

### 5.1 전체 흐름
```
질문 + RoutingDecision
   │
   ├─▶ [1] 질의 개선 (메인 모델): 대화 문맥 반영해 독립 질문으로 재작성, 정보원별 검색어 생성
   │
   ├─▶ [2] 검색 (병렬)
   │      ├ 로컬 RAG: 벡터 검색 + 키워드(BM25) 검색
   │      └ 웹 검색: 검색 API → 상위 URL 수집 → 본문 추출 → 청킹 → 임베딩
   │
   ├─▶ [3] 통합·재정렬: 중복 제거, 리랭커로 점수 재계산, 상위 N개 선택
   │      └ 근거 충분성 판정 (결정 모델 D6) → 부족하면 [1]로 돌아가 재검색 (최대 3회)
   │
   ├─▶ [4] 컨텍스트 조립: 토큰 예산 안에서 배치, 각 조각에 출처 번호 부여
   │
   ├─▶ [5] 생성: 출처 번호 인용([1], [2]) 지시와 함께 답변 생성
   │
   └─▶ [6] 검증 (결정 모델 D7): 답변 문장이 근거에 뒷받침되는지 확인, 근거 부족 시 "확인 불가" 표시
```

### 5.2 품질 원칙
- **근거 우선**: 근거가 없으면 지어내지 말고 모른다고 답한다.
- **출처 필수**: 문서명·줄 번호 또는 URL·제목·접속 시각을 표시한다.
- **최신성 판단**: 날짜에 민감한 질문(가격, 버전, 뉴스)은 웹 결과를 우선하고 날짜를 함께 표시한다.
- **충돌 표시**: 로컬 문서와 웹 정보가 다르면 둘 다 보여주고 차이를 명시한다.

### 5.3 인터넷 검색 설계
| 항목 | 내용 |
|------|------|
| 검색 백엔드 | **SearXNG (Colima 위 로컬 컨테이너, 무료, API 키 불필요)** 로 확정. 유료 API는 사용하지 않음 |
| 실행 방식 | Colima 위에서 `docker compose`로 SearXNG 컨테이너 실행, `127.0.0.1`에만 바인딩, JSON 출력 형식 활성화 |
| 검색 실행 여부 | **결정 모델이 판단** (최신성·외부 사실 필요 여부). 사용자는 `/web`으로 강제, 설정으로 전체 끄기 가능 |
| 본문 추출 | Jsoup + Readability 방식 본문 추출, 광고·메뉴 제거 |
| 동적 페이지 | JS 렌더링이 필요한 페이지는 P2 (Playwright 헤드리스) |
| 캐시 | 검색 결과·페이지 본문을 SQLite에 TTL 캐시 (기본 24시간) |
| 제한 | 요청당 최대 검색 3회, 페이지 8개, 페이지당 타임아웃 10초 |
| 예의 | robots.txt 준수, 도메인별 요청 간격 |
| 도메인 정책 | 허용/차단 도메인 목록, 신뢰 도메인 가중치 (예: 공식 문서) |

### 5.4 프라이버시 정책 (웹 검색)
- 웹 검색은 **외부로 데이터가 나가는 유일한 경로**다. 설정으로 전체 끄기 가능 (`web.enabled`).
- 웹 검색어는 **사용자 질문에서만 생성**한다. 로컬 문서·코드 내용을 검색어에 넣지 않는다.
- 웹 검색 실행 시 실제로 보낸 검색어를 UI와 로그에 표시한다.
- 기본값: 결정 모델 판단에 따라 자동 검색 (`web.mode: auto`). 옵션: `always` / `confirm`(매번 확인) / `off`.

---

## 6. 채널 — 웹 UI · 메신저

### 6.1 개념
- CLI, 웹 UI, 텔레그램 등 **모든 접속 경로(채널)는 같은 대화 엔진(`ConversationService`)** 을 쓴다.
- 채널 어댑터는 "메시지 받기 → `ConversationService` 호출 → 이벤트(토큰·상태·출처·승인 요청)를 채널 형식으로 보여주기"만 한다.
- 라우팅·검색·에이전트 로직은 채널과 무관하다. 새 메신저 추가 = 어댑터 하나 추가.

```
 CLI   웹 UI   Telegram   Discord   Slack
  │      │        │                 │
  ▼      ▼        ▼                 ▼
┌──────────────────────────────────────────────┐
│ Channel Adapters (채널별 입출력 변환)          │
└──────────────────────┬───────────────────────┘
                       ▼
┌──────────────────────────────────────────────┐
│ ConversationService                           │
│ 세션 조회 → Router → Agent → AgentEvent 스트림 │
│ ApprovalBroker (승인 요청을 보낸 채널로 전달)   │
└──────────────────────────────────────────────┘
```

### 6.2 운영 형태 — 상시 실행 서버
메신저로 언제든 쓰려면 앱이 항상 떠 있어야 한다.
- **`app-server`가 본체**다. macOS `launchd`로 로그인 시 자동 시작, 비정상 종료 시 자동 재시작.
- Ollama, Ollaya, SearXNG도 자동 시작되도록 등록한다.
- **CLI는 서버의 클라이언트**로 동작한다 (로컬 HTTP API 호출). 서버가 꺼져 있으면 임베디드 모드로 직접 실행한다.
- **잠자기 방지**: Mac이 잠들면 메신저 응답이 끊긴다. 전원 연결 시 시스템 잠자기를 끄거나 서버 실행 중 `caffeinate`로 잠자기를 막는다. 디스플레이 잠자기는 무관하다.

### 6.3 웹 UI
| 항목 | 내용 |
|------|------|
| 화면 | 대화 목록, 대화 화면(스트리밍, Markdown·코드 하이라이트, 출처 펼치기), 진행 상태 표시 |
| 승인 | 도구 실행 승인/거부/수정 다이얼로그 (셸 포함) |
| 관리 | 컬렉션 관리(폴더 등록·인덱싱 상태), 모델·메모리 사용량 대시보드, 설정, 라우팅 로그·평가 결과 |
| 통신 | Ktor 서버의 REST API + SSE(스트리밍) |
| 배포 | 빌드 결과(정적 파일)를 `app-server`가 함께 서빙. 별도 서버 불필요 |
| 접근 | 기본 `127.0.0.1`만. 다른 기기(휴대폰 등)에서 쓰려면 **Tailscale**(개인 무료)로 접속. 공유기 포트 개방은 하지 않음 |
| 인증 | 사용자별 비밀번호 로그인(Argon2) + 세션 쿠키, CSRF 방지. localhost 접속도 로그인 필요. 멀티 유저 전에는 OWNER 한 명 (7.12 U-02) |
| 기술 | **Compose Multiplatform Web (Kotlin/Wasm)** 확정. Ktor Client(Wasm)로 서버 호출, `api-model` 공유 모듈 사용. Markdown 렌더링·코드 하이라이트는 KMP 라이브러리(예: multiplatform-markdown-renderer, Highlights) 후보로 M9에서 검증 |

웹 UI가 데스크톱 앱 역할을 대신하므로 **Compose Desktop 앱은 보류**한다. 웹 UI를 Compose로 만들기 때문에, 나중에 데스크톱 앱이 필요하면 UI 코드 대부분을 재사용할 수 있다.

### 6.4 메신저 조사 (2026-10 기준)
집 Mac에서 돌리므로 **외부에서 들어오는 연결(공개 webhook)이 필요 없는 방식**을 우선한다.

| 메신저 | 연결 방식 | 공개 주소 필요 | 비용 | 종단간 암호화 | Kotlin/JVM 라이브러리 | 판단 |
|--------|-----------|----------------|------|---------------|------------------------|------|
| **Telegram** | Bot API long polling (`getUpdates`) | 불필요 | 무료 | 아니오 (Telegram 서버 경유·저장) | Ktor Client로 Bot API 직접 호출 또는 ktgbotapi(Kotlin) | **1순위 (P1)**. 인라인 버튼으로 승인 처리, 설정 쉬움 |
| **Discord** | Gateway WebSocket | 불필요 | 무료 | 아니오 | **Kord** (Kotlin, 코루틴 기반) | **2순위 (확정)**. 버튼 컴포넌트로 승인 |
| **Slack** | Socket Mode | 불필요 | 무료 플랜 가능 (기록 보존 제한) | 아니오 | Bolt for Java (Kotlin에서 사용) | **2순위 (확정)**. Block Kit 버튼으로 승인 |
| Signal | signal-cli 데몬 (JSON-RPC) | 불필요 | 무료 (봇용 전화번호 필요) | **예** | signal-cli 연동 | 보류 |
| Matrix | Client-Server API sync | 불필요 | 무료 (자체 서버 가능) | 예 (설정 시) | Trixnity(Kotlin) | 보류 |
| 카카오톡 | 카카오톡 채널 + 챗봇 스킬 서버 (webhook) | **필요** | 무료~유료 | 아니오 | 직접 구현 | **보류**. 개인용 봇 API가 없고, 응답 시간 제한이 짧아 LLM 생성에 부적합 |
| LINE | Messaging API webhook | 필요 | 무료 한도 있음 | 아니오 | LINE Bot SDK (Java) | 보류 |
| WhatsApp | Business Cloud API webhook | 필요 | 대화당 과금 가능 | 앱 간 예, API 경유 시 Meta 서버 처리 | 직접 구현 | 제외 (사업자 등록 필요) |

### 6.5 메신저 공통 기능
| 기능 | 내용 |
|------|------|
| 사용자 허용 목록 | 등록된 사용자의 메신저 계정 ID에서 온 메시지만 처리 (`ChannelBinding` → `userId`). 나머지는 무시하고 로그 기록 |
| 세션 매핑 | 메신저 대화방 ↔ 세션 연결. `/new`로 새 세션 |
| 명령어 | `/new` `/web` `/docs` `/code` `/task` `/status` `/cancel` (CLI·웹과 동일 체계) |
| 스트리밍 | 메시지를 일정 간격(약 1초)으로 수정해 생성 중 내용 표시. 플랫폼 rate limit 준수 |
| 긴 메시지 | 플랫폼 길이 제한(Telegram 4096자, Discord 2000자, Slack 블록당 3000자)에 맞춰 분할, 코드 블록이 깨지지 않게 자름 |
| 출처 표시 | 답변 끝에 출처 목록. 로컬 문서는 파일명·위치만 표시 |
| 승인 | 인라인 버튼(승인/거부). 응답 없으면 5분 후 자동 거부 |
| 파일 첨부 | 문서 파일을 보내면 "이 파일에 대해 질문" 또는 "컬렉션에 추가" 선택 (P1) |
| 음성 메시지 | 음성 → 텍스트 변환 후 질문, 원하면 음성으로 답변 (7.11 V-01·V-03) |
| 사진 | 사진을 보내면 비전 모델로 질문 (7.11 V-04). 생성한 이미지는 사진으로 전송 |
| 알림 | 예약 작업(T-11) 결과, 긴 작업 완료를 메신저로 푸시 (P2) |

### 6.6 채널 보안 · 프라이버시
- **메신저는 외부 서버를 거친다.** Telegram·Discord·Slack은 종단간 암호화가 아니므로, 질문과 답변(로컬 문서·코드 내용이 들어간 답변 포함)이 해당 회사 서버에 저장된다. **사용자 결정(v1.0): 메신저 답변에 로컬 정보 포함 허용** (`channels.<이름>.allowLocalSources: true` 기본값). 필요하면 채널별로 끌 수 있다.
- **봇 토큰 보관**: macOS 키체인 또는 환경 변수에 둔다. 설정 파일에 평문으로 쓰지 않는다.
- **메신저 셸 승인 허용 (v1.0 결정)**: Telegram·Discord·Slack에서도 셸(EXEC) 승인이 가능하다 (`channels.<이름>.allowExecApproval: true`). 메신저 계정이 탈취되면 원격 셸 실행으로 이어질 수 있으므로 다음 안전장치를 함께 적용한다.
  - 승인 메시지에 실행할 명령 전체·작업 디렉터리·이유를 그대로 표시한다.
  - 승인 버튼은 요청한 사용자 본인만 누를 수 있다. 셸(EXEC) 승인은 OWNER 계정만 가능하다 (7.12). 다른 사람이 누르면 무시하고 로그를 남긴다.
  - 승인 요청은 한 번만 쓸 수 있고 5분 뒤 만료된다.
  - 차단 목록·경로 제한·타임아웃은 채널과 관계없이 똑같이 적용한다.
  - 메신저 계정에는 2단계 인증을 켜 둔다 (운영 체크리스트).
- **웹 UI**: localhost 바인딩, 로그인 필수, 외부 접근은 Tailscale로만.
- **감사 로그**: 어느 채널에서 어떤 요청·승인이 있었는지 기록한다.

---

## 7. 기능 정의

우선순위: **P0** = MVP 필수, **P1** = MVP 직후, **P2** = 향후.

### 7.1 모델 / 엔진 관리 (Model)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| M-01 | 엔진 연결 | P0 | OpenAI 호환 API로 로컬 엔진 연결 (기본 Ollama) |
| M-02 | 모델 목록 조회 | P0 | 엔진에서 사용 가능한 모델 표시 |
| M-03 | 역할별 모델 매핑 | P0 | general/code/embedding/reranker (+ 결정 모델은 `decision` 설정) |
| M-04 | 생성 파라미터 | P0 | temperature, top_p, max_tokens, context length (에이전트별 기본값) |
| M-05 | 메모리 예산 관리 | P0 | 로드된 모델 메모리 추적, 70% 상한 초과 시 LRU 언로드 |
| M-06 | 엔진 상태 확인 | P0 | 엔진 미기동 시 안내 메시지 |
| M-07 | 모델 다운로드/삭제 | P1 | 엔진 API로 모델 pull/delete |
| M-08 | 엔진 교체 | P1 | mlx-lm server / vllm-mlx로 교체 가능 (설정만 변경) |
| M-09 | 성능 지표 | P1 | 토큰/초, 첫 토큰 지연, 메모리 사용량 기록 |

### 7.2 결정 모델 / 라우팅 (Router)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| R-01 | 명시적 지정 | P0 | `/chat` `/docs` `/web` `/code` `/task`, `@컬렉션` 으로 직접 지정 |
| R-02 | 규칙 기반 판정 | P0 | 파일 경로·코드 블록·URL 등 확실한 신호 처리 |
| R-03 | Ollaya 연동 | P0 | `DecisionClient`로 `POST /api/decide` 호출 (TypeSafe/Jev 호환 규격) |
| R-04 | 에이전트 선택 (D1) | P0 | `choice` 질문으로 에이전트 결정 |
| R-05 | 정보원 판정 (D2, D3) | P0 | 웹 필요·로컬 문서 필요를 `noul` 확률로 판정, 복수 선택 가능 |
| R-06 | 신뢰도 처리 | P0 | 임계값 미만이면 메인 모델 JSON 판정으로 폴백, 그래도 애매하면 ChatAgent + 선택지 제시 |
| R-06a | 컬렉션 선택 (D4) / 난이도 (D5) | P1 | `choice` / `score` 질문 추가 |
| R-06b | 품질 판정 (D6, D7) | P1 | 근거 충분성·근거 검증을 결정 모델로 수행 |
| R-06c | 에이전트 판정 (D8, D9) | P1 | 도구 위험도 점수, 작업 완료 여부 |
| R-06d | 인젝션 탐지 (D10) | P1 | Qwen3Guard 등으로 웹 페이지 검사 |
| R-07 | 결정 표시·수정 | P1 | 선택된 에이전트·확률 표시, 사용자가 즉시 변경 |
| R-08 | 라우팅 로그 | P1 | 질문·결정·사용자 수정 여부 기록 |
| R-09 | 라우팅 평가셋 | P1 | 질문 → 정답 에이전트 데이터셋, 정확도 측정 명령 |
| R-09a | 신뢰도 보정 | P1 | 평가셋으로 임계값·온도 보정 (laya:multilingual은 보정값 미포함) |
| R-10 | 피드백 학습 | P2 | 사용자 수정 이력을 라벨 데이터로 쌓아 결정 모델 파인튜닝 |
| R-11 | 다중 에이전트 협업 | P2 | 한 질문을 여러 에이전트가 나눠 처리 후 결과 통합 |

### 7.3 공통 대화 (Chat Core)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| C-01 | 메시지 송수신 | P0 | |
| C-02 | 스트리밍 응답 | P0 | 토큰 단위 실시간 출력 (Kotlin Flow) |
| C-03 | 세션 저장/불러오기 | P0 | |
| C-04 | 응답 중단 | P0 | 생성 중 취소 (Coroutine cancel) |
| C-05 | 진행 상태 표시 | P0 | "라우팅 → 검색 중(웹 3건) → 생성 중" 단계 표시 |
| C-06 | 시스템 프롬프트 편집 | P1 | 에이전트별 기본값 + 세션별 덮어쓰기 |
| C-07 | 컨텍스트 관리 | P1 | 토큰 한도 초과 시 오래된 메시지 요약/절삭 |
| C-08 | 대화 검색/내보내기 | P2 | Markdown 내보내기 |
| C-09 | 답변 피드백 | P1 | 👍/👎, 답변 수정본, 라우팅 정정("이건 웹 검색이었어야 해") 저장. 학습 데이터(7.10)와 평가셋의 원천 |

### 7.4 문서 RAG (Docs)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| K-01 | 컬렉션 관리 | P0 | 주제/프로젝트별 지식 베이스 생성·삭제, 설명 입력 (라우팅에 사용) |
| K-02 | 문서 등록 | P0 | 파일/폴더 지정해 인덱싱 |
| K-03 | 파싱 | P0 | .txt, .md, .pdf |
| K-04 | 청킹 | P0 | 문단/헤더 기준 분할 + 겹침 |
| K-05 | 임베딩 | P0 | `embedding` 역할 모델 사용 |
| K-06 | 하이브리드 검색 | P0 | 벡터 + 키워드(BM25) 결합 |
| K-07 | 리랭크 | P0 | `reranker` 역할 모델로 재정렬 |
| K-08 | 출처 표시 | P0 | 답변에 참고 문서·위치 표시 |
| K-09 | 질의 재작성 | P1 | 대화 문맥 반영 독립 질문화, 다중 검색어 |
| K-10 | 증분 인덱싱 | P1 | 해시 비교로 변경 파일만 재인덱싱 |
| K-11 | 폴더 감시 | P1 | 등록 폴더 변경 시 자동 재인덱싱 |
| K-12 | 근거 검증 | P1 | 답변 문장별 근거 확인 |
| K-13 | 추가 포맷 | P2 | .docx, .html, .hwp 등 |

### 7.5 웹 검색 (Web)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| W-01 | 검색 백엔드 연동 | P0 | SearXNG (무료, 로컬 컨테이너 — Colima) |
| W-02 | 페이지 수집·본문 추출 | P0 | 상위 URL 병렬 수집, 본문 추출 |
| W-03 | 웹 결과 RAG | P0 | 수집 본문 청킹·임베딩·리랭크 후 컨텍스트로 사용 |
| W-04 | URL 출처 표시 | P0 | 제목, URL, 접속 시각 |
| W-05 | 웹 검색 on/off | P0 | 전역 설정 + 세션별 설정 |
| W-06 | 검색어 표시 | P0 | 외부로 보낸 검색어를 사용자에게 표시 |
| W-07 | 캐시 | P1 | 검색 결과·본문 TTL 캐시 |
| W-08 | 도메인 정책 | P1 | 허용/차단/신뢰 도메인 |
| W-09 | URL 직접 읽기 | P1 | 질문에 포함된 URL을 읽고 답변 |
| W-10 | 반복 검색 | P1 | 결과 부족 시 검색어 보완해 재검색 (최대 3회) |
| W-11 | 웹 페이지 저장 | P2 | 유용한 페이지를 로컬 컬렉션에 저장 |
| W-12 | 동적 페이지 렌더링 | P2 | Playwright 헤드리스 |

### 7.6 코드 도우미 (Code)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| D-01 | 코드베이스 등록 | P0 | 저장소 경로 등록, `.gitignore` 반영 |
| D-02 | 코드 인덱싱 | P0 | 파일 단위 + 함수/클래스 단위 청킹 |
| D-03 | 코드 질의 | P0 | "이 기능 어디서 처리해?" → 관련 코드 검색 후 설명 |
| D-04 | 파일 지정 질의 | P0 | 특정 파일/범위를 컨텍스트로 첨부해 질문 |
| D-05 | 코드 리뷰 | P1 | `git diff` 또는 지정 파일 리뷰 |
| D-06 | 수정안 제시 | P1 | unified diff로 수정안 출력, 승인 시 적용 |
| D-07 | 심볼 인식 청킹 | P1 | tree-sitter 등으로 언어별 구문 단위 분할 |
| D-08 | 공식 문서 참고 | P1 | 라이브러리 질문 시 웹 검색으로 공식 문서 참조 |
| D-09 | 프로젝트 요약 | P1 | 디렉터리 구조·주요 모듈 자동 요약 |
| D-10 | IDE 연동 | P2 | IntelliJ 플러그인 또는 OpenAI 호환 API로 연결 |

### 7.7 작업 에이전트 / 도구 (Task)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| T-01 | 도구 인터페이스 | P0 | 이름, 설명, JSON 스키마, 실행, 위험 등급 |
| T-02 | 도구 실행 루프 | P0 | tool call 파싱 → 실행 → 결과 재주입, 최대 반복 횟수 제한 |
| T-03 | 승인 정책 | P0 | 위험 등급별 자동 실행 / 매번 승인 / 금지 |
| T-04 | 경로 화이트리스트 | P0 | 파일 도구가 접근 가능한 폴더 제한 |
| T-05 | 기본 도구 | P0 | 시각, 계산기, 파일 목록/읽기/검색, 문서 검색, 코드 검색, 웹 검색 |
| T-06 | 쓰기 도구 | P1 | 파일 쓰기/수정 (항상 승인) |
| T-07 | 셸 실행 도구 | P1 | **매번 사용자 승인 후 실행** (아래 셸 실행 정책 참고) |
| T-08 | 실행 로그 | P1 | 각 단계(계획/도구 호출/결과) 기록·표시 |
| T-09 | 작업 계획 표시 | P1 | 실행 전 단계별 계획 제시 |
| T-10 | MCP 클라이언트 | P1 | 외부 MCP 서버(stdio/Streamable HTTP)의 도구·리소스·프롬프트 사용. 첫 연동 대상은 Orchard (7.7a) |
| T-11 | 예약 작업 | P2 | 주기적으로 에이전트 작업 실행 |

위험 등급: `READ`(자동) / `NETWORK`(웹 접근, 설정에 따름) / `WRITE`(승인) / `EXEC`(매번 승인).

#### 셸 실행 정책 (T-07)
- **매번 승인**: 셸 명령은 실행할 때마다 승인받는다. "항상 허용" 같은 자동 승인은 두지 않는다.
- **승인 화면**: 실행할 명령 전체, 작업 디렉터리, 에이전트가 이 명령을 실행하려는 이유를 보여준다. 사용자는 승인 / 거부 / 명령 수정 후 승인 중 고른다.
- **차단 목록**: 승인 여부와 관계없이 실행하지 않는 명령 패턴을 둔다. 예: `sudo`, `rm -rf /`, `rm -rf ~`, 디스크 포맷, 시스템 설정 변경, `curl ... | sh` 같은 원격 스크립트 실행.
- **작업 디렉터리 제한**: 경로 화이트리스트(T-04) 안에서만 실행한다.
- **실행 제한**: 기본 타임아웃 60초, 출력 최대 길이 제한(초과분은 잘라서 전달), 백그라운드 프로세스 금지.
- **환경 변수**: API 키 등 민감한 환경 변수는 셸 실행 환경에서 제외한다.
- **기록**: 실행한 명령, 승인 여부, 종료 코드, 출력 요약을 `ToolRun`에 저장한다.
- **결정 모델 연계**: D8(도구 위험도)이 높게 나온 명령은 승인 화면에 경고를 표시한다.

### 7.7a Apple 앱 연동 — Orchard (MCP)

#### 개요
- **Orchard** (https://orchard.5km.tech/, 이 Mac에 v0.6.4 설치됨): Apple 앱을 AI 도구로 열어 주는 macOS 앱. Swift로 작성됐고 EventKit·MusicKit·WeatherKit·MapKit 등을 쓴다. "데이터가 기기 밖으로 나가지 않는다"고 명시한다.
- MCP 서버 실행: `/Applications/Orchard.app/Contents/MacOS/orchard-cli mcp` (**stdio 전용**, 포트를 열지 않음). CLI는 실행 중인 Orchard.app과 로컬 Unix 소켓으로 통신한다.
- 제공 항목 (2026-10-16 `tools/list` 실측, MCP 프로토콜 `2025-06-18`): **도구 52개, 프롬프트 11개, 리소스 19개**.
- 요금: Reminders·Calendar·Clock은 무료, Mail·Notes·Messages·Contacts·Music·Maps·Weather·Shortcuts는 **Pro**.
- **Pro를 구매해서 쓴다** (v1.4 결정, 2.4의 예외). 앱 11개를 모두 Orchard로 연동한다. 구매 후 Orchard 앱에서 로그인하고 기능 스위치(`orchard-cli features`)와 macOS 권한을 켠다.
- 현재 상태 (2026-10-16 확인): **Pro 로그인, 기능 11개 모두 켜짐.** 읽기 전용 도구 8종(`clock_time`, `calendar_info`, `reminder_info`, `notes_search`, `mail_accounts`, `weather_get`, `contacts_search`, `location_current`) 호출 성공. `orchard-cli status`는 location·contacts 권한을 `missing`으로 표시하지만 실제 호출은 데이터를 반환함 (상태 표시와 실제가 다를 수 있으므로 S-03 상태 확인은 `status`와 시험 호출을 함께 쓴다). `/usr/local/bin/orchard` 심볼릭 링크는 없음 → 설정에 번들 경로를 그대로 쓴다.

#### 연동 방식
- Vesta는 **범용 MCP 클라이언트**(`mcp` 모듈, 공식 Kotlin MCP SDK `io.modelcontextprotocol:kotlin-sdk`)를 두고, Orchard를 첫 번째 서버로 등록한다. 다른 MCP 서버도 설정만으로 추가한다.
- `app-server`가 `orchard-cli mcp`를 **자식 프로세스로 띄워 stdio로 연결**한다. 비정상 종료 시 지수 백오프로 재시작한다.
- macOS 권한(TCC)은 Orchard.app이 가진다. Vesta(JVM)는 Apple 데이터 권한을 직접 받지 않는다.
- Orchard.app이 떠 있어야 한다. 로그인 항목에 등록하고, 상태 확인(S-03)에서 `orchard-cli status`로 실행 여부·기능·권한을 보여 준다.
- 시작 시 `tools/list`로 도구를 받아 `McpTool`(Vesta `Tool` 구현)로 감싼다. 서버가 `tools/list_changed`를 보내면 다시 읽는다.
- **도구가 52개라 한 번에 모델에 넣지 않는다.** 로컬 모델은 도구가 많을수록 선택 정확도가 떨어지고 컨텍스트를 많이 쓴다. D11(앱 그룹 선택)로 고른 그룹의 도구만 노출한다 (보통 3~10개). 도구 이름 앞부분(`calendar_`, `reminder_`, `mail_` …)으로 그룹을 나눈다.

#### 앱 그룹과 도구
| 그룹 | 요금 | 조회 (READ) | 생성·수정·삭제 (WRITE) | 외부 발송·임의 실행 (EXEC) |
|------|------|-------------|------------------------|----------------------------|
| calendar | 무료 | `calendar_info`, `calendar_convert` | `calendar_event_create/update/delete` | — |
| reminders | 무료 | `reminder_info` | `reminder_create/update/delete`, `reminder_list_create/update/delete` | — |
| clock | 무료 | `clock_time`, `clock_util` | — | — |
| mail | Pro | `mail_read`, `mail_accounts`, `mail_scheduled_list` | `mail_mark`, `mail_refresh`, `mail_scheduled_cancel` | `mail_send` |
| notes | Pro | `notes_search`, `notes_get_content` | `notes_create`, `notes_update`, `notes_open` | — |
| messages | Pro | `messages_read`, `messages_scheduled_list` | `messages_scheduled_cancel` | `messages_send` |
| contacts | Pro | `contacts_search`, `contacts_get_details` | `contacts_create/update/delete` | — |
| music | Pro | `music_info`, `music_search` | `music_play`, `music_playlist_*` (`music_control`은 설정으로 READ 취급) | — |
| maps | Pro | `location_search/geocode/route/current` | — | — |
| weather | Pro | `weather_get` | — | — |
| shortcuts | Pro | `shortcuts_list`, `shortcuts_folders` | `shortcuts_open` | `shortcuts_run` |

#### 위험 등급 매핑
MCP `annotations`는 서버가 주는 **힌트일 뿐**이라 그대로 믿지 않는다. 아래 순서로 정한다.
1. 설정의 `mcp.servers.<이름>.toolOverrides`가 있으면 그 값을 쓴다.
2. 없으면 annotations로 정한다: `readOnlyHint: true` → `READ`, 그 밖 → `WRITE`. `destructiveHint: true`면 승인 화면에 "삭제·덮어쓰기" 경고를 표시한다.
3. annotations도 없으면 `WRITE`로 본다.
4. **남에게 보내거나 임의 동작을 하는 도구**(`mail_send`, `messages_send`, `shortcuts_run`)는 기본값을 `EXEC`로 고정한다. 셸과 같이 **매번 승인**하고 "항상 허용"을 두지 않는다. 승인 화면에 받는 사람, 제목, 본문 전체, 예약 시각(있으면)을 보여 준다. 단축어는 이름과 입력값을 보여 준다.
5. D8(도구 위험도)이 더 높게 나오면 높은 쪽을 쓴다 (기존 규칙과 같음).

#### 리소스와 프롬프트
- **리소스**(`orchard://calendar/today`, `orchard://reminders/due-today`, `orchard://mail/unread-summary`, `orchard://weather/current` 등 19개)는 읽기 전용 컨텍스트로 쓴다. `PersonalAgent`가 필요할 때 읽어 프롬프트에 넣는다.
- **프롬프트**(`daily-briefing`, `inbox-triage`, `schedule-meeting`, `message-catchup` 등 11개)는 슬래시 명령으로 노출한다. 예: `/briefing` → `daily-briefing`, `/inbox` → `inbox-triage`.

#### 보안 · 프라이버시
- **인젝션**: 메일·메시지·메모 본문은 남이 쓴 글이라 신뢰하지 않는다. 도구 결과는 "데이터" 구역으로 감싸 모델에 넣고, D10(인젝션 탐지)을 적용한다. 메일·메시지를 읽은 뒤 이어지는 발송·삭제는 승인 정책 때문에 자동으로 실행되지 않는다.
- **메신저 채널**: 메일·메시지 내용이 Telegram·Discord·Slack 서버를 거친다. 기존 결정(메신저 답변에 로컬 정보 허용)을 따라 기본 허용하고, 채널별로 끌 수 있게 한다 (`channels.<이름>.allowPersonalData`).
- **감사 로그**: 모든 MCP 도구 호출을 `ToolRun`에 서버 이름(`orchard`)과 함께 저장한다.

#### 기능 목록
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| A-01 | MCP 클라이언트 기반 | P1 | stdio 연결, `tools/list`·`tools/call`, 재시작, 상태 확인 (T-10) |
| A-02 | Orchard 등록 | P1 | 설정으로 서버 등록, 도구를 `McpTool`로 감싸기, 위험 등급 매핑 |
| A-03 | PersonalAgent + D11 | P1 | 앱 그룹 선택 후 해당 도구만 노출, 라우팅 평가셋에 개인 비서 질문 추가 |
| A-04 | 1차 검증 앱 | P1 | Calendar·Reminders·Clock부터 검증 (쓰기 도구가 단순해 승인 흐름 확인에 적합) |
| A-05 | Pro 앱 | P1 | Mail·Notes·Messages·Contacts·Music·Maps·Weather·Shortcuts. 발송·단축어 실행은 `EXEC` 매번 승인 |
| A-06 | 발송 승인 화면 | P1 | `mail_send`·`messages_send`·`shortcuts_run`을 채널별 승인 UI로 표시 |
| A-07 | 리소스·프롬프트 | P2 | 리소스를 컨텍스트로, 프롬프트를 슬래시 명령(`/briefing` 등)으로 노출 |
| A-08 | 아침 브리핑 | P2 | 예약 작업(T-11)으로 `daily-briefing` 결과를 Telegram에 푸시 (캘린더·미리 알림·메일·날씨) |
| A-09 | 무료 자체 도구 (대비책) | 보류 | Orchard 장애·서비스 중단·구독 종료 시에만 구현하는 Kotlin 내장 도구. 아래 표 참고 |

#### A-09 무료 대체 수단 (보류 — Orchard를 쓸 수 없게 될 때)
| 앱 | 대체 방법 | 비고 |
|----|-----------|------|
| Notes · Mail · Contacts · Music | **고정 AppleScript/JXA**를 `osascript`로 실행하는 전용 도구 | 임의 스크립트가 아니라 도구별로 미리 정의한 스크립트만 실행 (셸 도구와 별개). macOS "자동화" 권한을 Vesta에 부여 |
| Mail (대안) | IMAP/SMTP 직접 연결 (Jakarta Mail, 오픈소스) | 앱 비밀번호는 키체인에 보관 |
| Messages (읽기) | `~/Library/Messages/chat.db` SQLite 읽기 전용 조회 | "전체 디스크 접근" 권한 필요. 발송은 AppleScript, 매번 승인 (`EXEC`) |
| Shortcuts | macOS 내장 `shortcuts run <이름>` CLI | `EXEC`, 매번 승인 |
| Weather | Open-Meteo API (무료, API 키 불필요) | 외부 호출 → `NETWORK` 등급 |
| Maps | 위치 검색·경로는 우선 지원하지 않음 | 필요 시 OpenStreetMap 계열 무료 API 검토 |

위험 등급과 승인 규칙은 Orchard 도구와 같은 표(7.7a)를 따른다.

### 7.8 인터페이스 (Interface)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| I-01 | CLI | P0 | 대화형 터미널, 진행 상태·승인 프롬프트·출처 표시. 서버 클라이언트 + 임베디드 모드 |
| I-02 | 상시 실행 서버 | P1 | `app-server` launchd 등록, 자동 재시작, 잠자기 방지 |
| I-03 | 로컬 HTTP API | P1 | 자체 REST + SSE, OpenAI 호환 `/v1/chat/completions` |
| I-04 | 웹 UI | P1 | 대화·승인·컬렉션·모델 대시보드 (6.3) |
| I-05 | Telegram 봇 | P1 | long polling, 사용자 허용 목록, 인라인 버튼 승인 (6.4~6.6) |
| I-06 | Discord · Slack 봇 | P1 | Kord / Bolt(Socket Mode) 어댑터, 버튼 승인 |
| I-06a | 기타 메신저 | 보류 | Signal, Matrix |
| I-07 | 채널 간 세션 이어가기 | P2 | 웹에서 하던 대화를 텔레그램에서 계속 |
| I-08 | 데스크톱 GUI | 보류 | 웹 UI로 대체 |

### 7.9 설정 / 운영 / 평가 (System)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| S-01 | 설정 파일 | P0 | `~/.vesta/config.yaml` |
| S-02 | 로깅 | P0 | 파일 로깅 (라우팅·검색·도구 실행 포함) |
| S-03 | 상태 확인 명령 | P0 | 엔진, 로드된 모델, 메모리 사용량, 인덱스, 검색 백엔드, MCP 서버(Orchard 실행·권한) 상태 |
| S-04 | 품질 평가 명령 | P1 | 질문·정답 평가셋으로 라우팅 정확도, 검색 적중률, 답변 근거율 측정 |
| S-05 | 데이터 백업 | P2 | DB·인덱스 내보내기 |

### 7.10 모델 학습 (Learn) — LoRA 로컬 파인튜닝
**목적**: 쓰면서 쌓인 기록으로 "내 질문"에 맞게 모델을 다듬는다. 학습은 전부 이 Mac에서 하고, 평가를 통과한 결과만 반영한다.

#### 학습 대상
| 대상 | 무엇을 개선 | 데이터 | 도구 | 산출물 |
|------|-------------|--------|------|--------|
| **결정 모델** (Laya / decider) | 한국어 라우팅·판정 정확도 (D1~D3, D11) | `RoutingLog.userOverride`, 피드백(C-09), 라우팅 평가셋 | PyTorch(MPS) + Transformers. Laya는 ModernBERT/mmBERT 기반 인코더라 MLX가 아닌 PyTorch로 학습 | ONNX 내보내기 → Ollaya에 로컬 모델로 등록 |
| **생성 모델** (general) | Vesta 도구 호출 형식, 한국어 말투, 자주 쓰는 작업 | 👍 받은 대화, 사용자가 고친 답변, 도구 호출 성공 기록 | `mlx_lm.lora` (LoRA/QLoRA) | 어댑터(safetensors) → `mlx_lm.fuse` → Ollama에 새 모델로 등록 |
| 코드 모델 (code) | 내 코드베이스 스타일 | 승인·적용된 diff | `mlx_lm.lora` | 어댑터 (P2) |

#### 학습 흐름
```
기록 수집(C-09, RoutingLog) → 데이터셋 내보내기(JSONL, 개인정보 필터) → 학습 작업(job)
  → 평가 게이트(S-04 평가셋, 기존 모델과 비교) → 통과 시 새 버전 등록·전환 / 실패 시 폐기
  → 문제가 생기면 이전 버전으로 즉시 롤백
```

#### 기능 목록
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| L-01 | 학습 데이터 수집 | P1 | 피드백(C-09), 라우팅 정정, 도구 호출 결과를 학습 후보로 표시 |
| L-02 | 데이터셋 내보내기 | P1 | JSONL(chat 형식 / 분류 형식). 비밀번호·토큰·전화번호 등 개인정보 패턴 제거. 메일·메시지 원문(Orchard)은 기본 제외 |
| L-03 | 결정 모델 미세조정 | P1 | 한국어 라우팅 데이터로 Laya 미세조정 → ONNX → Ollaya 등록. 4.7a(`decider` 전환) 전에 먼저 시도 |
| L-04 | 생성 모델 LoRA | P2 | `mlx_lm.lora` QLoRA. 학습 중 메모리 확보를 위해 유지보수 시간대에 실행 |
| L-05 | 학습 작업 관리 | P1 | Kotlin이 Python 스크립트(uv 환경)를 자식 프로세스로 실행, 진행률·손실값을 웹 UI에 표시, 취소 가능 |
| L-06 | 평가 게이트 | P1 | 기존 버전 대비 평가 점수가 떨어지면 반영하지 않음. 결과를 `TrainingRun`에 기록 |
| L-07 | 버전 관리·롤백 | P1 | `ModelVersion`으로 어댑터·모델 버전 보관, 설정 한 줄로 이전 버전 복귀 |
| L-08 | 유지보수 시간대 | P2 | 예: 매일 03:00~06:00. 학습 중 메신저 요청에는 "학습 중, 응답이 느릴 수 있음" 안내 |
| L-09 | 사용자별 데이터 동의 | P1 | 멀티 유저일 때 학습에 쓸 수 있는 건 동의한 사용자의 데이터만 (7.12) |

### 7.11 이미지 · 음성 (Vision & Voice)
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| V-01 | 음성 입력 (STT) | P1 | Whisper large-v3-turbo(mlx-whisper). 웹 UI 마이크 버튼, Telegram·Discord 음성 메시지. ffmpeg로 OGG/Opus → WAV |
| V-02 | 음성 명령 확인 | P1 | 인식한 텍스트를 먼저 보여 준다. **음성으로 들어온 요청의 승인(WRITE·EXEC)은 음성으로 처리하지 않고 버튼으로만** |
| V-03 | 음성 출력 (TTS) | P2 | 1차 macOS `say`(한국어 음성 Yuna, 추가 메모리 0), 2차 MeloTTS. 답변 중 코드·표·출처는 읽지 않고 요약만 읽음 |
| V-04 | 이미지 입력 (비전) | P1 | 사진·스크린샷을 보여 주고 질문 ("이 오류 화면 뭐야?"). 웹 UI 붙여넣기·드래그, 메신저 사진 |
| V-05 | 이미지 내 문자 인식 | P2 | 스캔 PDF·사진 문서를 비전 모델로 텍스트화해 RAG 컬렉션에 추가 |
| V-06 | 이미지 생성 | P2 | `ImageAgent`: 메인 모델이 한국어 요청을 영어 프롬프트로 바꾸고 mflux(FLUX.1-schnell)로 생성. `/image` 명령 또는 D1 판정 |
| V-07 | 이미지 편집 | 보류 | 인페인팅·변형. 모델·메모리 검토 후 결정 |
| V-08 | 생성물 보관 | P2 | 생성 이미지를 `MediaAsset`으로 저장, 사용자별 갤러리 |

- 미디어 모델(mflux, mlx-whisper, MeloTTS)은 Python 패키지라 **로컬 미디어 워커**(`127.0.0.1` HTTP, uv 환경, launchd 등록) 하나로 묶어 띄운다. 앱은 `media` 모듈의 클라이언트로 호출한다.
- 생성 이미지에는 메타데이터에 "AI 생성(Vesta, 모델명)"을 기록한다.

### 7.12 멀티 유저 (Users)
**범위**: 한 Mac을 가족·소규모 팀(최대 5명 정도)이 같이 쓴다. 모델은 하나를 공유하고, 데이터와 권한은 사용자별로 나눈다.

#### 역할
| 역할 | 할 수 있는 것 |
|------|---------------|
| `OWNER` (Mac 주인, 1명) | 전체 설정, 사용자 관리, 모델·학습 관리, 셸(EXEC) 승인, **Apple 앱(Orchard) 사용** |
| `MEMBER` | 대화, 자기 컬렉션·공유 컬렉션 검색, 웹 검색, 자기 경로 안에서 파일 읽기·쓰기(승인), 이미지·음성 |
| `GUEST` (선택) | 대화·웹 검색만. 로컬 문서·도구 없음 |

#### 원칙
- **Orchard(Apple 앱)는 OWNER 전용.** Mail·Messages·Contacts 등은 Mac 주인 계정의 데이터이므로 다른 사용자는 쓸 수 없다. `PersonalAgent`는 OWNER에게만 라우팅된다.
- **셸(EXEC)은 OWNER만 승인**할 수 있다. MEMBER의 요청에서 셸이 필요하면 거부하거나 OWNER에게 승인 요청을 보낸다 (설정).
- **데이터 분리**: 세션·메시지·피드백·생성물은 사용자별. 컬렉션은 `PRIVATE`(본인만) / `SHARED`(지정 사용자) 중 선택. 검색은 접근 가능한 컬렉션만 대상으로 한다.
- **경로 화이트리스트**는 사용자별로 따로 둔다 (T-04).
- **동시 요청**: 메인 모델은 하나이므로 요청 큐를 둔다. 사용자별 공정 순서(라운드 로빈), 동시 생성 2개까지 (`OLLAMA_NUM_PARALLEL=2`, KV 캐시 예산 12GB 안에서 측정 후 조정).
- **학습 데이터**: 동의한 사용자의 데이터만 학습에 쓴다 (L-09).

#### 기능 목록
| ID | 기능 | 우선순위 | 설명 |
|----|------|----------|------|
| U-01 | 사용자·역할 관리 | P1 | OWNER가 웹 UI·CLI에서 사용자 추가·삭제·역할 변경 |
| U-02 | 사용자별 인증 | P1 | 웹 UI: 사용자별 비밀번호(Argon2 해시) + 세션 쿠키. 메신저: 메신저 계정 ID ↔ 사용자 매핑 (`ChannelBinding.userId`) |
| U-03 | 데이터 분리 | P1 | 모든 데이터에 `userId`. 조회는 항상 사용자 범위로 제한 (저장소 계층에서 강제) |
| U-04 | 공유 컬렉션 | P1 | 컬렉션 공유 대상 지정, 인덱스는 한 번만 만들고 접근 권한만 나눔 |
| U-05 | 요청 큐·공정 스케줄링 | P1 | 사용자별 라운드 로빈, 대기 순번 표시, 사용자별 하루 사용량 표시 |
| U-06 | 역할 기반 도구 정책 | P1 | 역할별 허용 도구·위험 등급 표. Orchard·셸은 OWNER 전용 |
| U-07 | 사용자별 설정 | P2 | 기본 에이전트, 말투(시스템 프롬프트), 음성 출력 여부 |
| U-08 | 원격 접속 | P2 | Tailscale 노드 공유(개인 무료 플랜 최대 3명) 또는 같은 공유기(LAN) 안에서만 접속 허용 중 선택 |

---

## 8. 시스템 아키텍처

```
┌────────────────────────────────────────────────────────────────┐
│                    Channel Layer (6장)                         │
│  CLI │ Web UI │ Telegram │ Discord │ Slack │ OpenAI API          │
└───────────────────────────────┬────────────────────────────────┘
                                │  Ktor Server (상시 실행, REST + SSE)
┌───────────────────────────────▼────────────────────────────────┐
│                     ConversationService                        │
│     세션 관리 · Router 호출 · Agent 실행 · ApprovalBroker       │
└───────────────────────────────┬────────────────────────────────┘
                                │
┌───────────────────────────────▼────────────────────────────────┐
│                    Router Layer (System 1)                     │
│ ExplicitRule → HeuristicRule → DecisionModel → (LLM 폴백)       │
└───────────────────────────────┬────────────────────────────────┘
                                │
┌───────────────────────────────▼────────────────────────────────┐
│                          Agent Layer                           │
│ Chat │ Docs │ WebResearch │ Code │ Task │ Personal │ Image (…Agent)│
└───────┬───────────────────────┬────────────────────────┬───────┘
        │                       │                        │
┌───────▼────────┐  ┌───────────▼────────────┐  ┌────────▼───────┐
│ Inference      │  │ Retrieval Pipeline     │  │ Tools          │
│ ModelManager   │  │ QueryRewriter          │  │ File / Code    │
│ (역할·예산·LRU) │  │ LocalRetriever (벡터+BM25)│ │ Web / Shell    │
│ ├ Ollama-MLX   │  │ WebRetriever           │  │ ApprovalPolicy │
│ ├ mlx-lm       │  │ Reranker               │  │ PathSandbox    │
│ └ vllm-mlx     │  │ ContextAssembler       │  └────────────────┘
└───────┬────────┘  │ GroundingChecker       │
        │           └───────────┬────────────┘
┌───────▼────────┐  ┌───────────▼────────────┐  ┌────────────────┐
│ MLX 엔진       │  │ Storage (SQLite)       │  │ SearXNG        │
│ (별도 프로세스) │  │ 세션/문서/청크/웹캐시   │  │ (Colima 컨테이너)│
└────────────────┘  └────────────────────────┘  └────────────────┘
┌────────────────────────────────────────────┐
│ Ollaya (별도 프로세스, :11435, ONNX/CPU)   │  ← Router·Retrieval·Agent가 판정 시 호출
│ laya / decider / Qwen3Guard                │
└────────────────────────────────────────────┘
┌────────────────────────────────────────────┐
│ Orchard MCP (orchard-cli mcp, stdio 자식)  │  ← Tools(McpTool)가 호출
│   └ Unix 소켓 → Orchard.app → Apple 앱     │
└────────────────────────────────────────────┘
┌────────────────────────────────────────────┐
│ 미디어 워커 (127.0.0.1, uv/Python)          │  ← media 모듈이 호출
│ mflux(이미지) · mlx-whisper(STT) · MeloTTS  │
├────────────────────────────────────────────┤
│ 학습 작업 (필요 시 실행, mlx-lm · PyTorch)   │  ← training 모듈이 실행·감시
└────────────────────────────────────────────┘
※ ConversationService 앞단에 Identity(사용자·역할 확인)와 요청 큐가 있다 (7.12)
```

### 8.1 핵심 인터페이스 (개념)
```kotlin
enum class ModelRole { GENERAL, CODE, EMBEDDING, RERANKER, VISION, IMAGE, STT, TTS }
enum class AgentType { CHAT, DOCS, WEB, CODE, TASK, PERSONAL, IMAGE }
enum class SourceType { LOCAL_DOCS, CODE, WEB }

// --- 결정 모델 (Ollaya /api/decide, TypeSafe 호환) ---
sealed interface DecisionQuestion {
    val instructions: String
    data class Choice(override val instructions: String, val criteria: Map<String, String>) : DecisionQuestion
    data class Score(override val instructions: String, val levels: List<String>) : DecisionQuestion
    data class Noul(override val instructions: String) : DecisionQuestion
}

sealed interface DecisionAnswer {
    data class Choice(val value: String, val confidence: Double, val probs: Map<String, Double>) : DecisionAnswer
    data class Score(val level: Double, val confidence: Double) : DecisionAnswer
    data class Noul(val probability: Double) : DecisionAnswer
}

interface DecisionClient {
    suspend fun decide(state: String, questions: Map<String, DecisionQuestion>): Map<String, DecisionAnswer>
}

data class RoutingDecision(
    val agent: AgentType,
    val agentConfidence: Double,
    val webProbability: Double,        // D2
    val localProbability: Double,      // D3
    val sources: Set<SourceType>,      // 확률 ≥ 임계값인 정보원
    val collections: List<String>,
    val difficulty: Int?,
    val stage: RoutingStage,           // EXPLICIT, RULE, DECISION_MODEL, LLM_FALLBACK
)

interface Router {
    suspend fun route(input: UserInput, history: List<Message>): RoutingDecision
}

// --- 채널 / 대화 ---
enum class ChannelType { CLI, WEB, TELEGRAM, DISCORD, SLACK, SIGNAL, MATRIX, OPENAI_API }

interface ConversationService {
    fun send(channel: ChannelRef, sessionId: SessionId?, input: UserInput): Flow<AgentEvent>
    suspend fun resolveApproval(requestId: String, decision: ApprovalDecision, via: ChannelRef)
    suspend fun cancel(sessionId: SessionId)
}

interface ChannelAdapter {
    val type: ChannelType
    suspend fun start(conversation: ConversationService)   // 메시지 수신 루프 (long polling 등)
    suspend fun stop()
}

interface Agent {
    val type: AgentType
    fun handle(input: UserInput, decision: RoutingDecision, session: Session): Flow<AgentEvent>
    // AgentEvent: Status, Token, Source, ToolCall, ApprovalRequest, Media(이미지·음성), Done
}

interface Retriever {
    val source: SourceType
    suspend fun retrieve(queries: List<String>, options: RetrieveOptions): List<Evidence>
}

interface WebSearchClient {
    suspend fun search(query: String, limit: Int): List<SearchHit>
}

interface Reranker {
    suspend fun rerank(question: String, candidates: List<Evidence>, topN: Int): List<Evidence>
}

interface InferenceClient {                       // OpenAI 호환 API 기반
    suspend fun listModels(): List<ModelInfo>
    fun chat(request: ChatRequest): Flow<ChatChunk>
    suspend fun embed(model: String, texts: List<String>): List<FloatArray>
}

enum class RiskLevel { READ, NETWORK, WRITE, EXEC }

interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject          // JSON Schema
    val risk: RiskLevel
    suspend fun execute(args: JsonObject): ToolResult
}

// --- 사용자 (identity 모듈) ---
enum class UserRole { OWNER, MEMBER, GUEST }
data class UserContext(val userId: UserId, val role: UserRole)   // 모든 서비스 호출에 전달

// --- 미디어 (media 모듈) ---
interface SpeechToText { suspend fun transcribe(audio: ByteArray, language: String = "ko"): String }
interface TextToSpeech { suspend fun synthesize(text: String, voice: String? = null): ByteArray }
interface ImageGenerator { suspend fun generate(prompt: String, width: Int, height: Int, steps: Int): ByteArray }

// --- 학습 (training 모듈) ---
interface TrainingJobRunner {
    fun start(spec: TrainingSpec): Flow<TrainingProgress>        // step, loss, eta
    suspend fun cancel(runId: TrainingRunId)
}

// --- MCP (mcp 모듈) ---
interface McpServerConnection {
    val name: String                                    // 예: "orchard"
    suspend fun listTools(): List<McpToolInfo>          // name, description, inputSchema, annotations
    suspend fun callTool(name: String, args: JsonObject): ToolResult
    suspend fun listResources(): List<McpResourceInfo>
    suspend fun readResource(uri: String): String
    suspend fun getPrompt(name: String, args: Map<String, String>): List<ChatMessage>
}

class McpTool(                                          // MCP 도구를 Vesta Tool로 감싼 것
    val server: String,
    val group: String,                                  // calendar, mail … (D11 선택지)
    override val name: String,
    override val description: String,
    override val parameters: JsonObject,
    override val risk: RiskLevel,                       // 7.7a 위험 등급 매핑 결과
    private val connection: McpServerConnection,
) : Tool { /* execute = connection.callTool(name, args) */ }
```

---

## 9. 기술 스택

| 영역 | 선택 | 비고 |
|------|------|------|
| 언어/런타임 | Kotlin 2.x, JVM 21 | 무료 OpenJDK 배포판(Temurin), Gradle toolchain으로 자동 설치 |
| 빌드 | Gradle (Kotlin DSL) | 현재 IntelliJ 기본 프로젝트 → Gradle 전환 |
| 비동기 | kotlinx.coroutines, Flow | 검색 병렬 처리, 스트리밍 |
| 직렬화 | kotlinx.serialization | |
| HTTP 클라이언트 | Ktor Client | 엔진·검색·페이지 수집 |
| HTTP 서버 | Ktor Server (REST + SSE) | 상시 실행, 웹 UI 정적 파일 서빙 |
| 추론 엔진 | **Ollama (MLX 엔진)** / mlx-lm / vllm-mlx | 별도 프로세스 |
| 결정 모델 런타임 | **Ollaya** (Rust, ONNX Runtime) | 별도 프로세스, `:11435` |
| 결정 모델 | **Laya** (`laya`), 한국어 부족 시 `decider` | |
| 웹 검색 | **SearXNG (Docker)** | 무료 |
| 컨테이너 | **Colima** + Docker CLI (Homebrew) | 무료 오픈소스. SearXNG 실행. VM 메모리 2GB 상한 (`colima start --memory 2`) |
| HTML 본문 추출 | Jsoup + Readability 포팅(readability4j 등) | |
| DB | SQLite + Exposed | |
| 벡터 검색 | SQLite BLOB + JVM 코사인 검색 (초기) → sqlite-vec 등 | |
| 키워드 검색 | SQLite FTS5 | 하이브리드 검색 |
| 문서 파싱 | Apache PDFBox, CommonMark | |
| 코드 파싱 | tree-sitter (JVM 바인딩) | P1 |
| Git | JGit 또는 `git` CLI | |
| CLI | Clikt + JLine | |
| 웹 UI | **Compose Multiplatform Web (Kotlin/Wasm)** | Kotlin 통일 |
| 공유 모델 | Kotlin Multiplatform (`api-model`: JVM + Wasm) | 서버·웹 UI가 같은 DTO 사용, kotlinx.serialization |
| Telegram | Bot API 직접 호출(Ktor Client) 또는 ktgbotapi | long polling |
| Discord | Kord | Kotlin 코루틴 기반 |
| Slack | Bolt for Java (Socket Mode) | Kotlin에서 사용 |
| MCP 클라이언트 | 공식 Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) | stdio 전송, 범용 |
| Apple 앱 연동 | **Orchard** (MCP 서버, `orchard-cli mcp`) | **Pro 구매해서 사용** (앱 11개). 대비책은 A-09 (보류) |
| 원격 접속 | Tailscale Personal (무료, 선택) | 휴대폰 등에서 웹 UI 접속 |
| 프로세스 관리 | launchd | 서버·엔진 자동 시작 |
| 비밀 보관 | macOS 키체인 | 봇 토큰, 웹 비밀번호 해시 |
| 설정 | Hoplite (YAML) | |
| 로깅 | kotlin-logging + Logback | |
| 테스트 | Kotest, MockK | |

---

## 10. 모듈 구조 — Gradle 멀티 모듈 (확정)

```
vesta/   (저장소 폴더: LocalAI/)
├── PROJECT.md
├── settings.gradle.kts
├── core/          # 도메인 모델, AgentType/Role, 공통 인터페이스, 설정
├── inference/     # InferenceClient (OpenAI 호환), ModelManager, 엔진별 어댑터
├── decision/      # DecisionClient(Ollaya), 질문 정의(D1~D11), 신뢰도 보정
├── router/        # 명시적/규칙/결정 모델 라우터, LLM 폴백, RoutingDecision, 평가셋 실행기
├── retrieval/     # QueryRewriter, LocalRetriever, Reranker, ContextAssembler, GroundingChecker
├── knowledge/     # 문서 파서, 청커, VectorStore, BM25 인덱스
├── web/           # WebSearchClient(SearXNG), 페이지 수집·본문 추출, 캐시
├── code/          # 코드 인덱싱, 심볼 청킹, git 연동
├── agents/        # Chat/Docs/Web/Code/Task/Personal/Image 에이전트
├── conversation/  # ConversationService, 세션 관리, ApprovalBroker
├── channels/
│   ├── channel-core/      # ChannelAdapter, 공통 포맷팅(분할·출처·승인 버튼)
│   ├── channel-telegram/  # P1
│   ├── channel-discord/   # Kord
│   └── channel-slack/     # Bolt Socket Mode
├── tools/         # Tool, 기본 도구, 승인 정책, 샌드박스
├── identity/      # 사용자·역할·인증, 사용자 범위 강제, 요청 큐
├── media/         # STT·TTS·이미지 생성 클라이언트, 오디오 변환(ffmpeg)
├── training/      # 데이터셋 내보내기, 학습 작업 실행·감시, 평가 게이트, 모델 버전
├── mcp/           # MCP 클라이언트(Kotlin SDK), 서버 프로세스 관리, McpTool, 위험 등급 매핑
├── storage/       # SQLite, 세션/문서/캐시 저장
├── app-cli/       # CLI 진입점
├── app-server/    # 상시 실행 서버 본체: Ktor API, 채널 어댑터 기동, 웹 UI 서빙
├── api-model/     # KMP 공유 모듈 (JVM + Wasm): 요청/응답 DTO, AgentEvent 직렬화 모델
├── web-ui/        # Compose Multiplatform Web (Kotlin/Wasm). 빌드 결과를 app-server가 서빙
├── build-logic/   # Gradle convention plugin (Kotlin/JVM 공통 설정, 테스트 설정)
└── gradle/libs.versions.toml   # 버전 카탈로그 (의존성 버전 한 곳에서 관리)
```

### 10.1 모듈 의존 규칙
```
app-cli / app-server
        │
        ▼
   channels-* ──▶ conversation
                      │
                      ▼
     agents ──────────────┬──────────────┬───────────┐
        │                 │              │           │
        ▼                 ▼              ▼           ▼
     router          retrieval         tools        code
        │             │      │           │           │
        ▼             ▼      ▼           │           │
     decision    knowledge   web         │           │
        │             │      │           │           │
        └──────┬──────┴──────┴───────────┴───────────┘
               ▼
     inference · storage
               │
               ▼
             core   (다른 모듈에 의존하지 않음)
```
- `core`는 도메인 모델과 인터페이스만 가진다. 외부 라이브러리 의존 최소화.
- 하위 모듈은 상위 모듈을 참조하지 않는다 (예: `knowledge` → `agents` 금지). 순환 의존 금지.
- 모듈 간 통신은 `core`의 인터페이스로 한다. 구현체 연결(DI)은 `app-*` 모듈에서 한다.
- 각 모듈은 단독으로 테스트 가능해야 한다 (외부 엔진은 가짜 구현으로 대체).
- `api-model`은 Kotlin Multiplatform(JVM + Wasm) 모듈로, `app-server`와 `web-ui`만 의존한다. JVM 전용 라이브러리를 넣지 않는다.
- 채널 어댑터는 `conversation`만 의존한다. 라우터·에이전트를 직접 호출하지 않는다.
- `identity`는 `core`·`storage`만 의존하고, `conversation`이 모든 요청 앞에서 호출한다. `media`·`training`은 `core`·`inference`·`storage`만 의존한다.
- `mcp`는 `tools`의 `Tool` 인터페이스를 구현하고 `core`에만 의존한다. `agents`는 `tools`를 통해서만 MCP 도구를 쓴다.
- 초기(M0~M1)에 필요한 모듈만 먼저 만든다: `core`, `inference`, `storage`, `agents`, `conversation`, `app-cli`. 나머지는 해당 마일스톤에서 추가한다.

---

## 11. 데이터 모델 (초안)

멀티 유저는 M15에서 켜지만, **`userId`는 M1부터 모든 사용자 데이터에 넣는다** (나중에 마이그레이션하지 않도록). 단일 사용자 동안은 OWNER 한 명으로 동작한다.

| 엔티티 | 주요 필드 |
|--------|-----------|
| User | id, name, role(OWNER/MEMBER/GUEST), passwordHash, trainingConsent, createdAt |
| Session | id, userId, title, originChannel, webEnabled, pinnedAgent, systemPrompt, createdAt, updatedAt |
| ChannelBinding | id, userId, channelType, externalUserId, externalChatId, sessionId, allowed, createdAt |
| ApprovalRequest | id, sessionId, toolRunId, requestedVia, resolvedVia, decision, expiresAt |
| Message | id, sessionId, role(system/user/assistant/tool), content, agent, toolCalls, createdAt |
| RoutingLog | id, messageId, stage(explicit/rule/decision/llm), decision(JSON), confidence, latencyMs, userOverride |
| DecisionLog | id, messageId, point(D1~D11), model, question, answer(JSON), latencyMs |
| Evidence | id, messageId, sourceType, refId(chunkId 또는 url), score, citedAs([n]) |
| Collection | id, ownerUserId, visibility(PRIVATE/SHARED), sharedWith, name, type(docs/code), description, rootPath, embeddingModel |
| Document | id, collectionId, path, language, contentHash, indexedAt |
| Chunk | id, documentId, index, text, startLine, endLine, symbol, embedding(BLOB) |
| WebPage | id, url, title, fetchedAt, contentHash, text, expiresAt |
| WebSearchLog | id, messageId, backend, query, resultCount, createdAt |
| Feedback | id, userId, messageId, rating(up/down), correctedText, correctedAgent, createdAt |
| MediaAsset | id, userId, sessionId, kind(image/audio), mimeType, path, prompt, model, createdAt |
| TrainingRun | id, target(decision/general/code), datasetHash, baseModel, params(JSON), status, metrics(JSON), startedAt, finishedAt |
| ModelVersion | id, role, name, adapterPath, trainingRunId, evalScore, active, createdAt |
| ToolRun | id, userId, sessionId, mcpServer(nullable), tool, args, result, risk, approved, approvedVia(channel), durationMs, createdAt |

---

## 12. 비기능 요구사항

- **프라이버시**: 추론·저장은 로컬. 외부 전송 경로는 ① 웹 검색어·페이지 요청, ② 메신저 채널(Telegram 등) 두 가지이며 각각 끌 수 있다. 웹 검색어에는 로컬 문서 내용을 넣지 않는다. 메신저로 로컬 정보를 보낼지는 채널별 설정. 텔레메트리 없음.
- **가용성**: 서버 상시 실행, 비정상 종료 시 자동 재시작, 엔진 미기동 시 메신저로도 상태 안내.
- **메모리**: 전체 사용량 128GB의 70%(약 89GB) 이하 유지.
- **성능 목표** (M0 측정 후 조정):
  - 결정 모델 판정 100ms 이내 (질문 묶음 1회 호출), LLM 폴백 포함 시 1초 이내
  - 첫 토큰 2초 이내 (일반 대화) / 5초 이내 (로컬 RAG) / 10초 이내 (웹 검색 포함)
  - 생성 속도 30 tok/s 이상 (메인 모델)
  - 문서 10,000개 / 코드 파일 50,000개 인덱싱 가능
- **품질 목표** (평가셋 기준):
  - 라우팅 정확도 90% 이상
  - RAG 검색 적중률(정답 근거가 상위 5개에 포함) 85% 이상
  - 출처 없는 사실 주장 비율 최소화
- **안정성**: 엔진 미기동, 모델 미설치, 메모리 부족, 검색 백엔드 장애를 명확히 안내. 웹 검색 실패 시 로컬 정보만으로 답하고 그 사실을 표시.
- **확장성**: 엔진, 검색 백엔드, 벡터 저장소, 에이전트, 도구를 인터페이스로 교체·추가 가능.
- **보안**: 경로 화이트리스트, 쓰기 도구 승인 필수, 셸 명령은 매번 승인 + 차단 목록 + 타임아웃 (메신저 셸 승인은 OWNER 계정·1회용·5분 만료). 웹 UI 로그인, 메신저 사용자 허용 목록, 토큰은 키체인 보관. 웹 페이지 내용 속 지시문은 데이터로만 취급 (프롬프트 인젝션 방어). 멀티 유저: 사용자 범위를 저장소 계층에서 강제, Orchard·셸은 OWNER 전용.

---

## 13. 마일스톤

| 단계 | 내용 | 완료 기준 |
|------|------|-----------|
| **M0. 준비** | Gradle 멀티 모듈 전환(build-logic, 버전 카탈로그, JDK toolchain), 초기 모듈 골격, 설정 로딩, Homebrew로 Ollama·Colima·Docker CLI 설치, SearXNG 컨테이너·Ollaya 설치, launchd 등록 초안, 모델 후보 벤치마크(라이선스 확인 포함) | `./gradlew build` 통과, 역할별 모델 확정, 메모리 실측표 |
| **M1. 대화** | InferenceClient, ModelManager, ChatAgent, ConversationService, CLI 스트리밍, 세션 저장 | CLI 대화, 재시작 후 이어하기, 예산 초과 시 언로드 동작 |
| **M2. 문서 RAG** | 컬렉션, 파싱, 하이브리드 검색, 리랭크, 출처 표시, DocsAgent | 등록 문서 기반 질문에 출처와 함께 답변 |
| **M3. 웹 검색** | SearXNG 연동, 본문 추출, 웹 RAG, WebResearchAgent, 프라이버시 설정 | 최신 정보 질문에 URL 출처와 함께 답변 |
| **M4. 결정 모델** | Ollaya·Laya 설치, DecisionClient, D1~D3 라우팅, LLM 폴백, 한국어 평가셋·신뢰도 보정 | 한국어 평가셋 라우팅 정확도 90% 이상 |
| **M5. 코드 도우미** | 코드 인덱싱, 코드 질의, CodeAgent | 저장소 질문에 정확한 파일·위치 답변 |
| **M6. 작업 에이전트** | Tool 루프, 승인 정책, 기본 도구, TaskAgent | "파일 찾아서 요약 후 저장" 시나리오 동작 |
| **M6a. Apple 앱 연동** | `mcp` 모듈, Orchard 등록, 위험 등급 매핑, PersonalAgent, D11, 발송 승인 화면, Calendar·Reminders·Clock 먼저 검증 후 Pro 앱 전체 | "내일 일정 알려줘", "장보기 미리 알림 추가", "안 읽은 메일 요약" 동작, 메일 발송은 승인 없이 실행되지 않음 |
| **M7. 품질 고도화** | 질의 재작성, 결정 모델 D4~D10, 증분 인덱싱, 코드 리뷰·diff 적용, 품질 평가 명령 | 품질 목표 달성 |
| **M8. 서버 · API** | app-server 상시 실행(launchd), REST + SSE, OpenAI 호환 API, CLI를 서버 클라이언트로 전환 | 재부팅 후 자동 기동, 외부 클라이언트 호출 성공 |
| **M9. 웹 UI** | `api-model` KMP 모듈, Compose Web 대화·승인·컬렉션·대시보드, 로그인, Tailscale 접속 | 휴대폰 브라우저에서 대화·승인 가능 |
| **M10. Telegram** | 봇 어댑터, 허용 목록, 스트리밍·분할, 인라인 승인, 파일 첨부 | 외출 중 텔레그램으로 문서 질의·웹 리서치 동작 |
| **M11. Discord · Slack** | Kord·Bolt 어댑터, 버튼 승인, 메시지 분할 | Discord·Slack에서 대화·셸 승인 동작 |
| **M12. 음성 · 비전 입력** | 미디어 워커(uv), mlx-whisper STT, ffmpeg, 웹 UI 마이크, 메신저 음성·사진, 비전 질문, `say` TTS | 텔레그램 음성으로 묻고 답 받기, 스크린샷 질문 동작 |
| **M13. 이미지 생성** | mflux + FLUX.1-schnell, `ImageAgent`, `/image`, MediaAsset 갤러리, 메모리 LRU 연동 | 한국어 요청으로 이미지 생성, 코드 모델과 메모리 충돌 없이 동작 |
| **M14. 모델 학습** | 피드백 수집(C-09), 데이터셋 내보내기, Laya 미세조정 → ONNX → Ollaya, 평가 게이트, 버전·롤백, 이후 생성 모델 QLoRA | 미세조정한 결정 모델이 한국어 평가셋에서 기존보다 높은 점수 |
| **M15. 멀티 유저** | identity 모듈, 사용자·역할, 사용자별 인증·데이터 분리, 공유 컬렉션, 요청 큐, 역할별 도구 정책 | 두 사용자가 서로의 대화·문서를 볼 수 없음, MEMBER는 Orchard·셸 사용 불가 |

### 13.1 M0 진행 기록 (2026-10-17)

#### 완료
- **Gradle 멀티 모듈**: Gradle 9.8.1 wrapper, Kotlin 2.4.21, JDK toolchain 21(foojay 자동 설치), `build-logic` convention plugin(`vesta.kotlin-jvm`·`-library`·`-application`·`-serialization`), 버전 카탈로그 `gradle/libs.versions.toml`.
- **초기 모듈**: `core`(도메인 enum·ID, `InferenceClient` 인터페이스, 설정 `VestaConfig`·`ConfigLoader`·`VestaPaths`), `inference`(`OpenAiCompatibleClient` 스트리밍·임베딩, `OllamaAdmin`), `storage`(SQLite WAL + Exposed, `users`·`schema_version`, OWNER 자동 생성), `agents`·`conversation`(빈 모듈, M1에서 채움), `app-cli`.
- **CLI** (`./gradlew :app-cli:installDist` → `app-cli/build/install/vesta/bin/vesta`):
  - `vesta status` — 설정·DB·Ollama·Ollaya·SearXNG·Orchard·메모리 예산 확인
  - `vesta config init | show` — `~/.vesta/config.yaml` 생성·검증
  - `vesta bench -m <모델> [--suite general|code]` — TTFT·tok/s·로드 시간·메모리, 결과 JSON(`~/.vesta/bench/`)에 응답 원문 저장
- **테스트**: 13개 통과 (`./gradlew build`).
- **인프라** (`scripts/setup-macos.sh`, 다시 실행해도 안전):
  - Homebrew: Ollama 0.40.5, Colima 0.10.3(VM 2CPU/2GB/20GB), Docker CLI 29.9.1, docker-compose 5.6.0, zstd, ffmpeg
  - Ollaya 0.13.0 (`~/.local/bin`, sudo 없음) + launchd `dev.vesta.ollaya` (`scripts/install-launchd.sh`)
  - SearXNG `2026.9.30-a9d990033` 컨테이너 (`infra/searxng/`, `127.0.0.1:8888`, JSON 형식 켬, 비밀 키는 `.env`)
  - 자동 시작: `brew services`(ollama, colima), launchd(ollaya), 컨테이너 `restart: unless-stopped`
- **결정 모델**: `laya`(`laya:en` 853MB + `laya:multilingual` 683MB) 설치.

#### 첫 측정: Laya 한국어 제로샷
질문 "내일 서울 날씨 어때? 우산 챙겨야 할까?" (정답: PERSONAL 또는 WEB, 웹 필요)
- 언어 분기는 정상: 한글 → `laya:multilingual`
- D1 결과: DOCS 0.36 / PERSONAL 0.35 / CHAT 0.12 / WEB 0.08 → **오답, 신뢰도 0.23**
- D2(웹 필요): 0.04 → **오답**
- 판정 26ms, 모델 로드 1.6초

→ 4.7의 우려(기본 체크포인트의 한국어 제로샷 성능 부족)가 첫 측정에서 확인됨. M4에서 영어 instructions·criteria 문구 조정, 한국어 평가셋 기반 보정, L-03 미세조정, `decider` 전환 순으로 검토한다. 임계값(0.6) 미만이라 지금 구조에서는 4단계(메인 모델 JSON 폴백)로 넘어가므로 동작은 안전하다.

#### 진행 중
- 생성·코드·임베딩 모델 후보 다운로드 (약 147GB). 메모리 예산(메인 ~40GB, 코드 ~20GB) 밖인 `qwen3.5:122b-a10b`(81GB), `gpt-oss:120b`(65GB)는 제외.

| 역할 | 후보 | 크기 | 엔진 |
|------|------|------|------|
| general | `qwen3.5:35b-a3b-mxfp8` | 37.2GB | MLX (safetensors) |
| general | `qwen3.5:35b-a3b-nvfp4` | 21.9GB | MLX |
| general | `qwen3.5:27b-mxfp8` (dense) | 29.9GB | MLX |
| general | `gpt-oss:20b` | 13.8GB | llama.cpp (GGUF) |
| code | `qwen3.5:35b-a3b-coding-nvfp4` | 21.9GB | MLX |
| code | `qwen3-coder:30b` | 18.6GB | llama.cpp |
| embedding | `qwen3-embedding:4b` / `bge-m3:567m` | 2.5GB / 1.2GB | llama.cpp |

#### 남은 일
- 후보 벤치마크 실행 → 역할별 모델 확정, 메모리 실측표 (2.2 갱신)
- 리랭커 후보 조사 (Ollama 기본 라이브러리에 없음 → M2 전에 실행 방식 결정)

---

## 14. 결정 사항 / 남은 질문

### 확정
- 용도: 일반 대화, 문서 질의, 웹 리서치, 코드 도우미, 작업 에이전트 모두 지원
- 하드웨어: Apple M5, 128GB, 메모리 예산 70%
- 인프라: 로컬·무료 원칙 (2.4). 컨테이너는 Colima. 유료는 Orchard Pro만
- Docker 호환 런타임 설치 (SearXNG 실행) — Docker Desktop 대신 Colima
- 코드 모델 분리 (`code` 역할에 코드 특화 모델, 필요 시 로드)
- 추론 엔진: MLX 기반 (1순위 Ollama MLX 엔진, OpenAI 호환 API로 교체 가능하게)
- 답변 품질: RAG + 인터넷 검색 + 리랭크 + 출처 인용
- 라우팅: 명시 지정 > 규칙 > 결정 모델 > 메인 모델 폴백 순
- 결정 모델: **Laya + Ollaya** (무료, 로컬). 판정은 결정 모델, 생성은 메인 모델로 분리
- Jev: 유료 호스팅 전용이라 제외 (API 규격만 호환)
- GLM-5.3-Flash: 사용 안 함 (로컬 메모리 초과, 외부 승격도 하지 않음)
- 결정 모델 전환: Laya 한국어 정확도 90% 미만이면 `decider`로 전환
- 웹 검색: SearXNG(무료), 검색 여부는 결정 모델이 판단 (`web.mode: auto`)
- 첫 인터페이스: CLI → 이후 상시 실행 서버 + 웹 UI + Telegram
- 채널 구조: 모든 채널이 ConversationService 공유, 어댑터만 추가
- 메신저 1순위: Telegram (long polling, 공개 주소 불필요). 카카오톡·LINE·WhatsApp은 보류/제외
- 데스크톱 앱(Compose Desktop): 보류, 웹 UI로 대체
- Kotlin 우선 원칙: 웹 UI도 Compose Multiplatform Web(Kotlin/Wasm), 서버·웹 공유 모델은 KMP `api-model`
- 메신저: Telegram → Discord · Slack 순. Signal·Matrix 보류
- 메신저 답변에 로컬 문서·코드 내용 포함 허용
- 메신저 셸 승인 허용 (OWNER 계정만, 1회용, 5분 만료, 차단 목록 동일 적용)
- 모듈 구성: Gradle 멀티 모듈 (10장 의존 규칙)
- 셸 실행: 도구로 포함, 매번 승인 후 실행 (차단 목록·타임아웃·경로 제한 적용)
- 범위 확대 (v1.5): 모델 학습(LoRA·결정 모델 미세조정), 이미지(비전 입력·생성)·음성(STT·TTS), 멀티 유저(최대 5명, OWNER/MEMBER/GUEST). 비목표는 클라우드 배포·대규모 사용자·사전학습·영상 생성·음성 복제·전이중 음성
- Apple 앱 연동: Orchard **Pro**를 구매해 MCP(stdio)로 연동 (무료 원칙의 유일한 예외), `PersonalAgent` 추가. 메일·메시지 발송과 단축어 실행은 매번 승인

### 남은 질문
- 없음. M0(준비) 단계 진행 가능.

### 구현 중 확인할 리스크
- Compose Multiplatform Web(Wasm)의 Markdown·코드 하이라이트·텍스트 선택 품질 (M9 초반에 시제품으로 확인)
- Laya 한국어 정확도 (M4, 부족하면 `decider`)
- Ollama MLX 엔진이 아직 프리뷰 (M0 벤치마크에서 안정성 확인)
- Orchard는 v0.6.x 초기 버전이라 도구 이름·스키마가 바뀔 수 있음. 시작 시 `tools/list`로 받고, 그룹 분류는 이름 접두사 규칙으로 처리해 변경에 견디게 한다
- Orchard는 외부 개발사의 유료 제품이라 가격 변경·서비스 중단 가능성이 있음. MCP 표준으로만 연동해 교체 가능하게 두고, 대비책은 A-09
- Ollaya가 직접 미세조정한 로컬 ONNX 모델을 불러올 수 있는지 (M14 전에 확인. 안 되면 Ollaya 호환 형식 조사 또는 결정 모델 서빙 대안 검토)
- mlx-lm LoRA의 MoE 모델(Qwen3.5-35B-A3B 등) 지원 수준과 학습 메모리 (M14에서 측정, 어려우면 dense 소형 모델 또는 결정 모델 학습만 먼저)
- 이미지 생성과 코드 모델이 동시에 필요할 때 메모리 경합 (M13에서 LRU 동작 확인)
- MeloTTS 한국어 품질과 설치 난이도 (안 되면 macOS `say` 유지)
- 멀티 유저 동시 요청 시 KV 캐시 증가 (M15에서 `OLLAMA_NUM_PARALLEL` 측정)
- 로컬 모델의 tool calling 정확도 (D11로 도구 수를 줄여 완화, M6a에서 측정)
