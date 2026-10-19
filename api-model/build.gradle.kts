plugins {
    id("vesta.kotlin-multiplatform")
    id("vesta.kotlin-serialization")
}

// 서버·CLI(JVM)와 웹 UI(JS)가 함께 쓰는 API 형식 (PROJECT.md 10장). JVM 전용 라이브러리를 넣지 않는다
kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
