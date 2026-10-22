package dev.vesta.core.config

/**
 * `~/.vesta/config.yaml` 구조 (PROJECT.md 4.9).
 * 모든 값에 기본값이 있어 설정 파일이 없어도 동작한다.
 */
data class VestaConfig(
    val inference: InferenceConfig = InferenceConfig(),
    val models: ModelsConfig = ModelsConfig(),
    val decision: DecisionConfig = DecisionConfig(),
    val web: WebConfig = WebConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val mcp: McpConfig = McpConfig(),
    val tools: ToolsConfig = ToolsConfig(),
    val server: ServerConfig = ServerConfig(),
    val channels: ChannelsConfig = ChannelsConfig(),
    val media: MediaConfig = MediaConfig(),
    val training: TrainingConfig = TrainingConfig(),
    val users: UsersConfig = UsersConfig(),
    val evolution: EvolutionConfig = EvolutionConfig(),
)

/**
 * 주간 자가 개선 (PROJECT.md 7.13, M16). launchd가 매주 `vesta evolve run`을 실행한다.
 * 새 모델 찾기 → 받기 → 지금 모델과 같은 평가 → 더 좋으면 메신저로 승인 요청. 설정값 조정·결정 모델 재학습은 평가를 통과하면 바로 반영한다.
 */
data class EvolutionConfig(
    val enabled: Boolean = true,
    /** 새 모델을 찾는 곳 (Ollama 라이브러리 웹 페이지) */
    val catalogUrl: String = "https://ollama.com",
    /** 새 태그를 지켜볼 모델 계열 */
    val families: List<String> = listOf("gemma4", "qwen3.6", "devstral"),
    /** 라이브러리 "새로 나온 모델"에서 도구 호출(tools)을 지원하는 계열도 후보로 본다 */
    val discoverNew: Boolean = true,
    /** 태그 고르기: 같은 크기면 앞에 있는 양자화를 고른다 (Apple Silicon은 MLX 태그가 빠르다) */
    val quantizations: List<String> = listOf("mxfp8", "nvfp4", "q8_0"),
    /** 이보다 큰 모델은 받지 않는다 (메인 모델과 임베딩·결정 모델이 함께 올라가야 한다) */
    val maxCandidateGigabytes: Int = 48,
    /** 한 번에 받아 평가할 후보 수 (받는 데 모델 하나에 30분~1시간 반) */
    val maxCandidates: Int = 2,
    /** 종합 점수가 지금 모델보다 이만큼 높아야 제안한다 (같은 모델을 두 번 재면 종합이 1%p 안팎 흔들린다) */
    val minImprovement: Double = 0.03,
    /** 답변 시작 시간(중앙값)이 지금 모델의 이 배수를 넘으면 탈락 */
    val maxSlowdown: Double = 1.5,
    /** 정정이 이만큼 새로 쌓였으면 결정 모델을 다시 학습한다 */
    val retrainMinCorrections: Int = 5,
    /** 라우터·D6 임계값을 평가셋으로 다시 맞춘다 */
    val tune: Boolean = true,
    /** 승인 요청을 이 시간 안에 처리하지 않으면 만료 */
    val proposalTtlDays: Int = 7,
    /** 평가셋 폴더 (scripts/install-vesta.sh가 저장소의 eval/을 복사한다) */
    val evalDir: String = "~/.vesta/evalsets",
    /** 근거율을 재는 문서 컬렉션 (eval/retrieval-project-md.tsv: PROJECT.md를 넣은 컬렉션). 없으면 [codeCollection] 질문으로 잰다 */
    val docsCollection: String = "b",
    /** D6 임계값을 맞추는 코드 저장소 (eval/code-vesta.tsv·code-absent-ko.tsv: 이 저장소) */
    val codeCollection: String = "vesta",
)

/** 멀티 유저 (PROJECT.md 7.12, M15) */
data class UsersConfig(
    /** 동시에 생성하는 요청 수. 넘으면 사용자별 차례(라운드 로빈)로 기다린다 (U-05). Ollama `OLLAMA_NUM_PARALLEL`과 맞춘다 */
    val maxParallel: Int = 2,
    /** MEMBER의 파일 도구 폴더. 사용자마다 `<memberRoot>/<이름>` (T-04). OWNER는 tools.allowedPaths */
    val memberRoot: String = "~/Vesta/users",
    /** 사용자 이름별 추가 허용 폴더 (MEMBER) */
    val paths: Map<String, List<String>> = emptyMap(),
)

/** 모델 학습 (PROJECT.md 7.10, M14). Python 학습기(trainer, uv)는 Vesta가 자식 프로세스로 실행한다 */
data class TrainingConfig(
    /** 학습기 프로젝트 (scripts/install-vesta.sh가 저장소의 trainer/를 복사한다) */
    val trainerDir: String = "~/.vesta/trainer",
    /** 변환한 기본 모델·실행 기록·어댑터를 두는 곳 */
    val workDir: String = "~/.vesta/training",
    val uv: String = "/opt/homebrew/bin/uv",
    /** 결정 모델 학습의 출발점. 학습한 버전 위에 쌓지 않고 항상 이 모델에서 새로 학습한다 */
    val decisionBase: String = "winnow:e4b",
    val epochs: Int = 2,
    val rank: Int = 16,
    val learningRate: Double = 1e-4,
    /** 평가 게이트: 다른 판정(D4~D11)이 지금 모델과 같아야 하는 비율 */
    val minReplayAgreement: Double = 0.95,
    /** 학습 중 쓰는 메모리 (그만큼 자리를 비운다) */
    val memoryGigabytes: Int = 30,
    /** Ollaya 모델 저장소 */
    val ollayaModels: String = "~/.ollaya/models",
)

/**
 * 음성·이미지 입력 (PROJECT.md 7.11, M12). 음성 인식 모델·워커 주소는 `models.stt`, 읽어 주기 음성은 `models.tts`.
 */
data class MediaConfig(
    /** 음성을 16kHz WAV로, 합성 음성을 m4a·ogg로 바꾼다 */
    val ffmpeg: String = "/opt/homebrew/bin/ffmpeg",
    /** 이보다 긴 음성은 앞부분만 인식한다 */
    val maxAudioSeconds: Int = 300,
    /** 받을 음성 파일 최대 크기 */
    val maxAudioMegabytes: Int = 25,
    /** 받을 이미지 최대 크기 */
    val maxImageMegabytes: Int = 10,
    /** 질문 하나에 붙일 수 있는 이미지 수 */
    val maxImages: Int = 4,
    /** 읽어 줄 글자 수 상한 (넘으면 앞부분만 읽는다) */
    val ttsMaxChars: Int = 1_500,
    /** 생성 이미지 기본 크기·단계 (FLUX.1-schnell은 4단계면 충분) */
    val imageSize: Int = 1024,
    val imageSteps: Int = 4,
    /** 이미지 모델이 쓰는 메모리 (그리기 전에 이만큼 자리를 비운다, 2.3) */
    val imageMemoryGigabytes: Int = 10,
)

/** 메신저 채널 (PROJECT.md 6.5·6.6). 봇 토큰은 설정 파일이 아니라 키체인(또는 환경 변수)에 둔다. */
data class ChannelsConfig(
    val telegram: TelegramConfig = TelegramConfig(),
    val discord: DiscordConfig = DiscordConfig(),
    val slack: SlackConfig = SlackConfig(),
)

/** 메신저마다 같은 설정 */
interface MessengerConfig {
    /** 토큰이 있을 때만 실제로 켜진다 (`vesta <채널> token`) */
    val enabled: Boolean
    /** 로컬 문서·코드·Apple 앱 내용이 들어간 답변을 보낼지 (v1.0 결정: 허용) */
    val allowLocalSources: Boolean
    /** 셸(EXEC) 승인을 메신저에서 받을지 (v1.0 결정: 허용) */
    val allowExecApproval: Boolean
    /** 생성 중 메시지 수정 간격 (플랫폼 rate limit 안쪽) */
    val streamIntervalMillis: Long
    /** 받을 문서 파일 최대 크기 */
    val maxFileMegabytes: Int
    /** API 주소 (시험용 가짜 서버를 쓸 때만 바꾼다) */
    val apiUrl: String
    /** 음성으로 물으면 답도 음성으로 보낼지 (V-03, 지원하는 메신저만). `/voice`로 대화방마다 바꿀 수 있다 */
    val voiceReply: Boolean get() = false
}

data class TelegramConfig(
    override val enabled: Boolean = true,
    override val allowLocalSources: Boolean = true,
    override val allowExecApproval: Boolean = true,
    /** Telegram은 채팅당 초당 1회 정도까지 */
    override val streamIntervalMillis: Long = 1_200,
    /** Bot API 다운로드 한도 20MB */
    override val maxFileMegabytes: Int = 20,
    /** 직접 띄운 Bot API 서버나 시험용 가짜 서버를 쓸 때만 바꾼다 */
    override val apiUrl: String = "https://api.telegram.org",
    override val voiceReply: Boolean = false,
) : MessengerConfig

data class DiscordConfig(
    override val enabled: Boolean = true,
    override val allowLocalSources: Boolean = true,
    override val allowExecApproval: Boolean = true,
    /** Discord는 대화방당 5초에 5번까지 고칠 수 있다 */
    override val streamIntervalMillis: Long = 1_500,
    /** 무료 계정 첨부 한도 10MB */
    override val maxFileMegabytes: Int = 10,
    override val apiUrl: String = "https://discord.com/api/v10",
    /** 음성으로 물으면 음성 메시지로도 답할지 (`!voice`로 대화방마다 바꿀 수 있다, M18) */
    override val voiceReply: Boolean = false,
) : MessengerConfig

data class SlackConfig(
    override val enabled: Boolean = true,
    override val allowLocalSources: Boolean = true,
    override val allowExecApproval: Boolean = true,
    /** chat.update는 분당 50번 정도까지 (Tier 3) */
    override val streamIntervalMillis: Long = 1_500,
    override val maxFileMegabytes: Int = 20,
    override val apiUrl: String = "https://slack.com/api",
) : MessengerConfig

/** 상시 실행 서버 (PROJECT.md 6.2, I-02·I-03). 기본은 이 Mac에서만 접속. */
data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8787,
    /** 웹 UI를 받을 추가 Host 이름 (DNS 리바인딩 방지). localhost·127.0.0.1·Tailscale `*.ts.net`은 항상 허용 */
    val allowedHosts: List<String> = emptyList(),
) {
    val baseUrl: String get() = "http://$host:$port"
}

/** 작업 도구 (PROJECT.md 7.7, 셸 실행 정책). */
data class ToolsConfig(
    /** 파일 도구·셸이 접근할 수 있는 폴더 (T-04). `~`는 홈. 코드 저장소 루트는 자동으로 더한다. */
    val allowedPaths: List<String> = listOf("~/Documents", "~/Desktop", "~/Downloads"),
    val shellEnabled: Boolean = true,
    val shellTimeoutSeconds: Long = 60,
    /** 도구 결과를 모델에 넘길 최대 글자 수 (넘으면 잘라 낸다) */
    val maxOutputChars: Int = 12_000,
    /** 에이전트 한 번에 도구를 부를 수 있는 최대 단계 (T-02) */
    val maxSteps: Int = 12,
    /** 승인 대기 시간. 넘으면 거부 (6.5) */
    val approvalTimeoutSeconds: Long = 300,
)

data class InferenceConfig(
    /** OpenAI 호환 API 주소. Ollama 기본값. */
    val baseUrl: String = "http://127.0.0.1:11434/v1",
    /** Ollama 전용 API(`/api/ps` 등) 주소. 메모리 측정에 쓴다. */
    val ollamaUrl: String = "http://127.0.0.1:11434",
    val requestTimeoutSeconds: Long = 600,
)

data class ModelSpec(
    val name: String,
    /** "forever"면 상시 모델: 메모리 예산 초과 시에도 내리지 않는다 (PROJECT.md 2.3). */
    val keepAlive: String? = null,
    val engine: String = "ollama",
    /** 추론 모델의 생각 양. M0 벤치마크 결과 기본 low (PROJECT.md 13.1). */
    val reasoningEffort: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Ollama가 아닌 엔진의 주소. 예: 리랭커 llama-server `http://127.0.0.1:8082` */
    val baseUrl: String? = null,
) {
    val alwaysOn: Boolean get() = keepAlive == "forever"
}

data class ModelsConfig(
    val general: ModelSpec? = null,
    val code: ModelSpec? = null,
    val embedding: ModelSpec? = null,
    val reranker: ModelSpec? = null,
    /** 없으면 메인 모델이 이미지를 읽는다 (Gemma 4는 비전 지원) */
    val vision: ModelSpec? = null,
    /** 이미지 생성: 미디어 워커의 mflux FLUX.1-schnell 4bit (M13). 10분 안 쓰면 워커가 내린다 */
    val image: ModelSpec? = ModelSpec(name = "FLUX.1-schnell", keepAlive = "10m", engine = "mflux", baseUrl = "http://127.0.0.1:11436"),
    /** 음성 인식: 미디어 워커의 mlx-whisper (M12). 설정 파일에 없어도 켜진다 */
    val stt: ModelSpec? = ModelSpec(
        name = "mlx-community/whisper-large-v3-turbo",
        keepAlive = "30m",
        engine = "mlx-whisper",
        baseUrl = "http://127.0.0.1:11436",
    ),
    /** 읽어 주기: macOS say 한국어 음성 */
    val tts: ModelSpec? = ModelSpec(name = "Yuna", engine = "say"),
)

data class DecisionThresholds(
    /** 결정 모델의 에이전트 판정을 받아들일 최소 신뢰도. 못 넘으면 메인 모델이 판정한다 (4.5 4단계). */
    val agent: Double = 0.6,
    val needsWeb: Double = 0.5,
    val needsLocal: Double = 0.5,
    /**
     * D6: 코드 검색 결과로 답할 수 있는 정도가 이보다 낮으면 "코드에서 찾지 못함" (CodeAgent).
     * 코드 조각은 문서보다 D6 점수가 낮게 나와 0.5보다 낮게 둔다 (M15: 있는 질문 최저 0.317, 없는 질문 최고 0.096)
     */
    val codeSufficient: Double = 0.2,
)

data class DecisionConfig(
    val engine: String = "ollaya",
    val baseUrl: String = "http://127.0.0.1:11435",
    /** M4 평가로 laya → winnow:e4b (PROJECT.md 13.5). decider는 macOS에서 CPU로만 돌아 5초 이상 걸려 제외. */
    val model: String = "winnow:e4b",
    val thresholds: DecisionThresholds = DecisionThresholds(),
    val fallback: String = "general",
    /** false면 결정 모델을 건너뛰고 바로 메인 모델이 판정한다. */
    val enabled: Boolean = true,
    /** 결정 모델 질문 문구 언어 (M4 평가: en이 ko보다 정확) */
    val criteriaLanguage: String = "en",
)

enum class WebMode { AUTO, ALWAYS, OFF }

/** 웹 검색 (PROJECT.md 5.3·5.4). OFF면 외부로 아무것도 보내지 않는다. */
data class WebConfig(
    val mode: WebMode = WebMode.AUTO,
    val searxngUrl: String = "http://127.0.0.1:8888",
    /** 질문 하나에 읽을 최대 페이지 수 */
    val maxPages: Int = 6,
    val pageTimeoutSeconds: Long = 10,
    val cacheHours: Long = 24,
    /** 읽지 않을 도메인 (하위 도메인 포함) */
    val blockedDomains: List<String> = emptyList(),
    val userAgent: String = "VestaBot/0.1 (+local personal assistant)",
)

data class MemoryConfig(
    /** 전체 통합 메모리 중 앱이 쓸 비율 (PROJECT.md 2.2). */
    val budgetPercent: Int = 70,
)

data class McpServerConfig(
    val transport: String = "stdio",
    val command: String,
    val args: List<String> = emptyList(),
    val enabled: Boolean = true,
    val toolOverrides: Map<String, String> = emptyMap(),
)

data class McpConfig(
    val servers: Map<String, McpServerConfig> = mapOf(
        "orchard" to McpServerConfig(
            command = "/Applications/Orchard.app/Contents/MacOS/orchard-cli",
            args = listOf("mcp"),
            toolOverrides = mapOf(
                "mail_send" to "EXEC",
                "messages_send" to "EXEC",
                "shortcuts_run" to "EXEC",
                "music_control" to "READ",
            ),
        ),
    ),
)
