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

application {
    applicationName = "vesta"
    mainClass = "dev.vesta.cli.MainKt"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED", // 터미널 라이브러리(JNA) 경고 제거
        "-Dkotlin-logging.logStartupMessage=false",
    )
}
