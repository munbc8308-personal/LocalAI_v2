// 서버(JVM)와 웹 UI(JS)가 함께 쓰는 Kotlin Multiplatform 모듈 (api-model)
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = the<LibrariesForLibs>()

group = "dev.vesta"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())

    js { browser() }
}

// Kotlin/JS가 Node.js·Yarn 저장소를 프로젝트에 직접 추가하지 않게 한다 (저장소는 settings.gradle.kts에 선언)
listOf(project, rootProject).forEach { target ->
    target.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        target.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec>().downloadBaseUrl.set(null as String?)
    }
    target.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
        target.extensions.findByType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec>()?.downloadBaseUrl?.set(null as String?)
    }
}
