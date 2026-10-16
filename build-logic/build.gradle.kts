plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    // convention plugin 안에서 버전 카탈로그(libs)를 타입 안전하게 쓰기 위한 설정
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
