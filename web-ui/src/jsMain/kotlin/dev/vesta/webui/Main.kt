package dev.vesta.webui

import org.jetbrains.compose.web.renderComposable

// 블록 본문이어야 한다: `fun main() = renderComposable(…)`은 Kotlin/JS에서 사용되지 않는 코드로 지워진다
fun main() {
    renderComposable(rootElementId = "root") { App() }
}
