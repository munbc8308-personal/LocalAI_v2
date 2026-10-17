plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":core"))
    implementation(project(":decision"))
    implementation(libs.kotlin.logging)
}
