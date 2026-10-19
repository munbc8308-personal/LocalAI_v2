plugins {
    id("vesta.kotlin-application")
    id("vesta.kotlin-serialization")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":inference"))
    implementation(project(":storage"))
    implementation(project(":agents"))
    implementation(project(":conversation"))
    implementation(project(":knowledge"))
    implementation(project(":retrieval"))
    implementation(project(":web"))
    implementation(project(":decision"))
    implementation(project(":router"))
    implementation(project(":tools"))
    implementation(project(":mcp"))
    implementation(libs.clikt)
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
