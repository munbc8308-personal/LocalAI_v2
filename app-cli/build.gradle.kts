plugins {
    id("vesta.kotlin-application")
    id("vesta.kotlin-serialization")
}

dependencies {
    implementation(project(":app-common"))
    implementation(project(":api-model"))
    implementation(project(":channel-telegram"))
    implementation(project(":channel-discord"))
    implementation(project(":channel-slack"))
    implementation(libs.clikt)
    implementation(libs.bundles.ktor.client)
    implementation(libs.jline)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.ktor.client.mock)
}

// Mordant(clikt 터미널)의 FFM 방식은 Windows의 새 JDK에서 콘솔 크기를 읽다 ClassCastException으로 죽는다 (RC-07 Windows 시험).
// 빼면 JNA 방식을 쓴다 (macOS도 같다)
configurations.configureEach {
    exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-ffm")
    exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-ffm-jvm")
}

application {
    applicationName = "vesta"
    mainClass = "dev.vesta.cli.MainKt"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED", // 터미널 라이브러리(JNA) 경고 제거
        "-Dkotlin-logging.logStartupMessage=false",
    )
}
