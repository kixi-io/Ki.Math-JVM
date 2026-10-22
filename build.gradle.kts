// Ki.Math-JVM/build.gradle.kts

plugins {
    `java-library`
    kotlin("jvm") version "2.3.0"
}

group = "io.kixi"
version = "0.1.0"
description = "Ki.Math: the `math` type bag (fits and lab statistics) for KD, KS, KQ and RevDB"

repositories {
    mavenCentral()
}

dependencies {
    // The bag's only dependency (Ki type bags design, 1, principle 4).
    api("io.kixi:Ki.Core-JVM:2.4.0")

    // The corpus round trip: KD text, KS literals and members, RevDB storage and indexes.
    testImplementation("io.kixi:Ki.KD-JVM:2.3.2")
    testImplementation("io.kixi:Ki.KS-JVM:2.3.3")
    testImplementation("io.kixi:RevDB-JVM:0.1.0-SNAPSHOT")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    // Kotest 6 asks for kotlin-reflect 2.2.x; pin it to the compiler's version, as Ki.KS does.
    testImplementation(kotlin("reflect"))
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
