plugins {
    id("vesta.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":router"))
    api(project(":identity"))
    implementation(libs.kotlin.logging)
}
