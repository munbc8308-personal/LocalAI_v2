plugins {
    id("vesta.kotlin-library")
}

// 사용자·역할·인증, 사용자 범위 강제, 요청 큐 (PROJECT.md 7.12, 10장). core·storage만 의존한다
dependencies {
    api(project(":core"))
    api(project(":storage"))
    implementation(libs.bouncycastle)
    implementation(libs.kotlin.logging)
}
