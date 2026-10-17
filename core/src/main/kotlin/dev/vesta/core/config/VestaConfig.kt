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
    val vision: ModelSpec? = null,
    val image: ModelSpec? = null,
    val stt: ModelSpec? = null,
    val tts: ModelSpec? = null,
)

data class DecisionThresholds(
    /** 결정 모델의 에이전트 판정을 받아들일 최소 신뢰도. 못 넘으면 메인 모델이 판정한다 (4.5 4단계). */
    val agent: Double = 0.6,
    val needsWeb: Double = 0.5,
    val needsLocal: Double = 0.5,
)

data class DecisionConfig(
    val engine: String = "ollaya",
    val baseUrl: String = "http://127.0.0.1:11435",
    /** 한국어 정확도가 부족하면 `decider`로 바꾼다 (PROJECT.md 4.7a). */
    val model: String = "laya",
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
