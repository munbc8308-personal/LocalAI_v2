plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    api(project(":knowledge"))
    implementation(project(":inference"))
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
}
