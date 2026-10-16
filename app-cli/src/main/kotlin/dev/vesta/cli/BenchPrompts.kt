package dev.vesta.cli

import com.github.ajalt.clikt.core.BadParameterValue

internal data class BenchPrompt(val id: String, val user: String, val system: String? = null)

/** 벤치마크 프롬프트. 한국어 품질·도구 호출 형식·긴 입력 처리를 함께 본다 (PROJECT.md 4.10 선정 기준). */
internal object BenchPrompts {
    val WARMUP = BenchPrompt("warmup", "안녕하세요. 한 문장으로 답해 주세요.")

    private val general = listOf(
        BenchPrompt("ko-explain", "MLX와 llama.cpp의 차이를 Apple Silicon 관점에서 다섯 문장 이내로 설명해 주세요."),
        BenchPrompt(
            "ko-summary",
            "다음 글을 세 줄로 요약해 주세요.\n\n" + LONG_TEXT,
        ),
        BenchPrompt(
            "tool-json",
            "내일 오전 10시에 '주간 회의' 일정을 만들어 줘.",
            system = """
                너는 도구를 호출하는 비서다. 반드시 JSON 한 개만 출력한다. 설명 문장은 쓰지 않는다.
                형식: {"tool": "calendar_event_create", "arguments": {"title": string, "start": ISO8601, "end": ISO8601}}
                오늘은 2026-10-17이고 시간대는 Asia/Seoul이다.
            """.trimIndent(),
        ),
        BenchPrompt("ko-reason", "사과 3개가 있는데 2개를 먹고 5개를 더 샀습니다. 그중 절반을 친구에게 주면 몇 개가 남나요? 풀이 과정을 짧게 보여 주세요."),
    )

    private val code = listOf(
        BenchPrompt("kt-write", "Kotlin으로 LRU 캐시를 구현해 주세요. LinkedHashMap을 쓰고, 용량 초과 시 가장 오래 안 쓴 항목을 제거합니다. 코드만 출력하세요."),
        BenchPrompt(
            "kt-review",
            """
                다음 Kotlin 코드의 버그를 찾아 고쳐 주세요.
                ```kotlin
                fun average(xs: List<Int>): Double {
                    var sum = 0
                    for (i in 0..xs.size) sum += xs[i]
                    return sum / xs.size
                }
                ```
            """.trimIndent(),
        ),
    )

    fun suite(name: String): List<BenchPrompt> = when (name) {
        "general" -> general
        "code" -> code
        else -> throw BadParameterValue("알 수 없는 suite: $name (general, code)")
    }
}

private val LONG_TEXT = """
    Apple Silicon은 CPU와 GPU가 하나의 통합 메모리를 공유한다. 그래서 큰 언어 모델을 돌릴 때 GPU 메모리로 데이터를 복사하는 과정이 없고,
    메모리 용량만 충분하면 데이터센터용 GPU 없이도 수십억 개 파라미터 모델을 실행할 수 있다. MLX는 이 구조에 맞춰 Apple이 만든 머신러닝
    프레임워크로, 지연 실행과 통합 메모리 활용을 기본으로 한다. 최근에는 Ollama 같은 도구가 MLX 엔진을 지원하면서 설치와 모델 관리가 쉬워졌다.
    다만 모델 포맷에 따라 실행 엔진이 달라지고, 일부 기능은 아직 프리뷰 단계라 안정성을 직접 확인해야 한다. 로컬 모델은 지식이 학습 시점에
    멈춰 있으므로, 최신 정보가 필요한 질문에는 검색 결과를 함께 넣어 주는 방식이 많이 쓰인다. 이때 검색 결과에 출처 번호를 붙이면 사용자가
    답변의 근거를 직접 확인할 수 있다. 개인 문서를 함께 검색하려면 문서를 작은 조각으로 나누고 임베딩을 만들어 저장해 두어야 한다.
""".trimIndent()
