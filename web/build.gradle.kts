plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    implementation(project(":storage"))
    implementation(libs.bundles.ktor.client)
    api(libs.ktor.client.okhttp)
    implementation(libs.jsoup)
    compileOnly(libs.jspecify)
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
}
