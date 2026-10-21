plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlin.logging)
}
