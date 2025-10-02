plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "kz.qwertukg"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.1"

val lwjglVersion = "3.3.4"
val os = org.gradle.internal.os.OperatingSystem.current()
val natives = when {
    os.isWindows -> if (System.getProperty("os.arch").contains("aarch64")) "natives-windows-arm64" else "natives-windows"
    os.isLinux   -> if (System.getProperty("os.arch").contains("aarch64")) "natives-linux-arm64"   else "natives-linux"
    else         -> if (System.getProperty("os.arch").contains("aarch64")) "natives-macos-arm64"  else "natives-macos"
}

dependencies {

    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")

    runtimeOnly("org.lwjgl:lwjgl::$natives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$natives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$natives")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.11.0")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(18)
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("MainKt")
}