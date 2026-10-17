// 모든 Kotlin/JVM 모듈 공통 설정: JDK toolchain, 컴파일 옵션, 테스트(Kotest)
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = the<LibrariesForLibs>()

group = "dev.vesta"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    compilerOptions {
        allWarningsAsErrors = false
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

dependencies {
    testImplementation(libs.bundles.testing)
    testRuntimeOnly(libs.logback.classic) // kotlin-logging(slf4j)이 테스트에서도 동작하도록
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
