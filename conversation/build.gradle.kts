plugins {
    id("vesta.kotlin-library")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlin.logging)
}
