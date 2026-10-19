plugins {
    id("vesta.kotlin-application")
    id("vesta.kotlin-serialization")
}

dependencies {
    implementation(project(":app-common"))
    implementation(project(":api-model"))
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
