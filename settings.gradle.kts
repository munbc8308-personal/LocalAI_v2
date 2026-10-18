pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // JDK toolchain(21)이 없으면 무료 OpenJDK 배포판을 자동으로 내려받는다
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "vesta"

// M0~M1 초기 모듈. 나머지(decision, router, retrieval, ...)는 해당 마일스톤에서 추가한다 (PROJECT.md 10장)
include(
    "core",
    "inference",
    "storage",
    "knowledge",
    "retrieval",
    "web",
    "decision",
    "router",
    "tools",
    "agents",
    "conversation",
    "app-cli",
)
