plugins {
    id("vesta.kotlin-library")
}

// 예약 작업 (PROJECT.md 7.23, M29): 예약 명령·실행기. 대화·저장소·보내기는 함수로 받는다
dependencies {
    api(project(":core"))
    implementation(libs.kotlin.logging)
}
