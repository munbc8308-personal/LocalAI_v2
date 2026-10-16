plugins {
    id("vesta.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(libs.bundles.exposed)
    implementation(libs.kotlin.logging)
}
