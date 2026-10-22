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
        // Compose 런타임이 쓰는 androidx 라이브러리
        google {
            content { includeGroupByRegex("androidx.*") }
        }
        // Kotlin/JS(web-ui) 빌드 도구: Node.js·Yarn 배포본. 프로젝트 저장소를 막아 두었으므로 여기서 선언한다
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
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
    "media",
    "training",
    "evolution",
    "identity",
    "mcp",
    "agents",
    "conversation",
    "channel-core",
    "channel-telegram",
    "channel-discord",
    "channel-slack",
    "app-common",
    "api-model",
    "web-ui",
    "app-cli",
    "app-server",
)

// 메신저 어댑터는 channels/ 아래에 모은다 (PROJECT.md 10장)
project(":channel-core").projectDir = file("channels/channel-core")
project(":channel-telegram").projectDir = file("channels/channel-telegram")
project(":channel-discord").projectDir = file("channels/channel-discord")
project(":channel-slack").projectDir = file("channels/channel-slack")
