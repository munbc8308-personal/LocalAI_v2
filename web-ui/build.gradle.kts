plugins {
    id("vesta.compose-web")
    id("vesta.kotlin-serialization")
}

// 웹 UI (PROJECT.md 6.3). Compose HTML(Kotlin/JS, DOM)로 만들고, 빌드 결과를 app-server가 서빙한다
kotlin {
    js {
        browser {
            commonWebpackConfig { outputFileName = "app.js" }
            // 브라우저 테스트(Karma)는 Chrome이 필요해 끄고, 단위 테스트는 Node.js로 돌린다
            testTask { enabled = false }
        }
        nodejs()
        binaries.executable()
    }
    sourceSets {
        jsMain.dependencies {
            implementation(project(":api-model"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            implementation(libs.kotlinx.coroutines.core)
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
