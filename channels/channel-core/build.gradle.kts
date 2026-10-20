plugins {
    id("vesta.kotlin-library")
    // 메신저 어댑터 시험이 함께 쓰는 가짜 대화 엔진·저장소
    `java-test-fixtures`
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlin.logging)
}
