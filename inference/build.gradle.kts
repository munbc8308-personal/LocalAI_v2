plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    api(libs.bundles.ktor.client)
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
}
