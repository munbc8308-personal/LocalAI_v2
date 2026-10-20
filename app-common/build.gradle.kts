plugins {
    id("vesta.kotlin-library")
}

// 구현체 연결 (PROJECT.md 10.1: DI는 app-* 모듈에서). app-cli·app-server가 같이 쓴다
dependencies {
    api(project(":core"))
    api(project(":inference"))
    api(project(":storage"))
    api(project(":agents"))
    api(project(":conversation"))
    api(project(":knowledge"))
    api(project(":retrieval"))
    api(project(":web"))
    api(project(":decision"))
    api(project(":router"))
    api(project(":tools"))
    api(project(":mcp"))
    api(project(":media"))
    api(project(":api-model"))
    implementation(libs.kotlin.logging)
    implementation(libs.bouncycastle)
}
