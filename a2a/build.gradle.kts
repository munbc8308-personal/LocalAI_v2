plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
}
