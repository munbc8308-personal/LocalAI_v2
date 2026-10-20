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
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlin.logging)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.ktor.server.test.host)
}

application {
    applicationName = "vesta-server"
    mainClass = "dev.vesta.server.ServerKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dkotlin-logging.logStartupMessage=false")
}

// 웹 UI(web-ui) 빌드 결과를 클래스패스 web/ 에 넣어 서버가 서빙한다 (PROJECT.md 6.3)
tasks.processResources {
    dependsOn(":web-ui:jsBrowserDistribution")
    from(rootProject.layout.projectDirectory.dir("web-ui/build/dist/js/productionExecutable")) {
        into("web")
        exclude("*.map", "composeResources/**")
    }
}
