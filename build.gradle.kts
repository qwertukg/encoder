plugins {
    kotlin("jvm") version "2.2.20"
}

group = "kz.qwertukg"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.itextpdf:itext7-core:8.0.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(18)
}

tasks.test {
    useJUnitPlatform()
}