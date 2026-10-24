package dev.vesta.app

/**
 * 하드웨어에 맞춘 모델 (PROJECT.md N-02, M23). Apple Silicon의 통합 메모리로 메인 모델을 고른다(모두 MLX 태그).
 * 메모리 예산(70%) 안에 메인 모델 + 임베딩·결정 모델 + 그림(FLUX 약 10GB, 쓸 때만)이 들어가게.
 * 결정 모델(winnow:e4b)·임베딩(bge-m3)은 모두 같다. 영상 클립(Wan2.2, 최대 48GB)은 96GB 이상에서만 권한다
 */
object HardwareProfile {
    data class Plan(
        val tier: String,
        /** 메인·코드·비전 모델 (Ollama) */
        val general: String,
        /** 받을 모델 대략 크기 (GB): 메인 + 임베딩 + 결정 모델 + 그림 + 음성 인식 */
        val downloadGigabytes: Int,
        /** 영상 클립을 권하는가 (모델 32GB 별도) */
        val clips: Boolean,
        val note: String,
    )

    /** 그대로 쓰는 것: 임베딩 1.2 + 결정 모델 약 8 + FLUX 약 10 + Whisper 1.6 */
    private const val SHARED_GB = 21

    fun recommend(totalBytes: Long): Plan {
        val gb = totalBytes / 1_000_000_000.0
        return when {
            gb >= 96 -> Plan("96GB 이상", "gemma4:26b-mxfp8", 28 + SHARED_GB, true, "메인 26B(MoE, 활성 4B) mxfp8. 영상 클립(Wan2.2)도 돌릴 수 있다")
            gb >= 60 -> Plan("64GB", "gemma4:26b-nvfp4", 16 + SHARED_GB, false, "메인 26B를 4비트(nvfp4)로. 영상 클립은 메모리가 모자라 권하지 않는다")
            gb >= 44 -> Plan("48GB", "gemma4:12b-mxfp8", 13 + SHARED_GB, false, "메인 12B mxfp8")
            gb >= 30 -> Plan("32GB", "gemma4:12b-nvfp4", 8 + SHARED_GB, false, "메인 12B 4비트. 그림을 그릴 때는 메인 모델을 잠시 내린다")
            else -> Plan("16~24GB", "gemma4:e4b-mxfp8", 6 + SHARED_GB, false, "메인 E4B. 그림·긴 문서는 느릴 수 있다. 16GB 미만은 권하지 않는다")
        }
    }

    /** 기본 설정 틀의 메인 모델(gemma4:26b-mxfp8)을 [plan]의 것으로 바꾼다 */
    fun apply(template: String, plan: Plan): String = template.replace("gemma4:26b-mxfp8", plan.general)
}
