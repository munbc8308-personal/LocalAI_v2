plugins {
    id("vesta.kotlin-library")
}

// Discord 음성 채널 (PROJECT.md I-08, M19). JDA가 음성 연결(UDP·Opus)을, JDAVE가 DAVE(종단 간 암호화)를 맡는다.
// JDAVE는 Java 25 FFM이라 클래스 버전이 높다: 실행은 서버 JVM(27)에서 하고, 컴파일은 이 모듈만 버전 확인을 끈다.
// 텍스트 봇(channel-discord, Ktor 직접)과 별도로 JDA가 Gateway를 하나 더 연다 (음성 상태만)
java { disableAutoTargetJvm() }

dependencies {
    api(project(":channel-discord"))
    implementation(libs.jda)
    implementation(libs.jdave.api)
    runtimeOnly(libs.jdave.native.darwin)
    // 기본 DAVE 구현: libdave-jvm(JNI). 못 쓰면 JDAVE로
    implementation(libs.libdave.adapter.jda)
    implementation(libs.libdave.impl.jni)
    runtimeOnly(libs.libdave.natives.darwin)
    // opus-java가 끌어오는 JNA 4는 Apple Silicon용이 없어 5로 올린다 (높은 버전이 이긴다)
    implementation(libs.jna)
    implementation(libs.kotlin.logging)
    testImplementation(project(":media"))
}
