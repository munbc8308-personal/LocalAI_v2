plugins {
    id("vesta.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":router"))
    implementation(libs.kotlin.logging)
}
