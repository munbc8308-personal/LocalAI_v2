plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

// 서버·클라이언트(CLI, M9 웹 UI)가 함께 쓰는 API 형식. M9에서 Kotlin Multiplatform(JVM + Wasm)으로 바꾼다 (PROJECT.md 10장)
dependencies {
    api(project(":core"))
}
