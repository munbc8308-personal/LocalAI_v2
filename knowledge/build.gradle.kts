plugins {
    id("vesta.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":storage"))
    implementation(libs.pdfbox)
    implementation(libs.kotlin.logging)
}
