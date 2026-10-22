plugins {
    id("vesta.kotlin-library")
}

// 주간 자가 개선 (PROJECT.md 7.13, M16): 모델 목록 읽기, 후보 고르기, 평가 비교 게이트, 임계값 조정, 제안 반영.
// 평가 자체(라우팅·답변·속도)는 앱 구성(AppContainer)이 필요해 app-cli가 돌리고, 여기에는 판정 규칙만 둔다
dependencies {
    api(project(":core"))
    api(project(":inference"))
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
}

dependencies {
    testImplementation(project(":storage"))
}
