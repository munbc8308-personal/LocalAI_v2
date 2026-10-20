plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
}
