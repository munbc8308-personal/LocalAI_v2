plugins {
    id("vesta.kotlin-library")
}

dependencies {
    api(project(":core"))
    implementation(project(":retrieval"))
    implementation(project(":storage"))
}
