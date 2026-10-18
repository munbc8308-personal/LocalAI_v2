plugins {
    id("vesta.kotlin-library")
}

dependencies {
    api(project(":core"))
    implementation(project(":knowledge"))
    implementation(project(":storage"))
    implementation(libs.kotlin.logging)
}
