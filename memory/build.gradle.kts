plugins {
    id("vesta.kotlin-library")
}

// 장기 기억 (PROJECT.md 7.21, M27): 저장·검색·명령·자동 기억. 저장소·임베딩·모델 호출은 함수로 받는다
dependencies {
    api(project(":core"))
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.serialization.json)
}
