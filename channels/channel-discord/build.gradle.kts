plugins {
    id("vesta.kotlin-library")
    id("vesta.kotlin-serialization")
}

dependencies {
    api(project(":channel-core"))
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlin.logging)
    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":channel-core")))
    // 가짜 Gateway·Socket Mode 서버 (WebSocket 연결 시험)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.websockets)
}
