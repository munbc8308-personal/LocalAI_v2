plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.hoplite.core)
    implementation(libs.hoplite.yaml)
}
