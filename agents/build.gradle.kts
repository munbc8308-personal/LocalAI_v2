plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    implementation(project(":retrieval"))
    implementation(project(":storage"))
    implementation(libs.kotlin.logging)
}
