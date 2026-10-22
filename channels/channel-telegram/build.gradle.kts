plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":channel-core"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
    implementation(libs.ktor.client.java)
    testImplementation(testFixtures(project(":channel-core")))
}
