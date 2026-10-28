// Ki.Math-JVM/build.gradle.kts

plugins {
    `java-library`
    `maven-publish`
    kotlin("jvm") version "2.3.0"
}

group = "io.kixi"
version = "0.2.0"
description = "Ki.Math: the `math` type bag (fits and lab statistics) for KD, KS, KQ and RevDB"

repositories {
    mavenCentral()
}

dependencies {
    // The bag's only dependency (Ki type bags design, 1, principle 4).
    api("io.kixi:Ki.Core-JVM:2.5.0")

    // The corpus round trip: KD text, KS literals and members, RevDB storage and indexes.
    testImplementation("io.kixi:Ki.KD-JVM:2.4.0")
    testImplementation("io.kixi:Ki.KS-JVM:2.4.0")
    testImplementation("io.kixi:RevDB-JVM:0.9.0")

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

// ============================================================================
// Publishing (GitHub Packages, as the other Ki libraries)
// ============================================================================

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/kixi-io/Ki.Math-JVM")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                groupId = "${project.group}"
                artifactId = "Ki.Math-JVM"
                name.set("Ki.Math")
                description.set(project.description)
                url.set("https://kixi.io")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://raw.githubusercontent.com/kixi-io/Ki.Math-JVM/master/LICENSE.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("dleuck")
                        name.set("Daniel Leuck")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/kixi-io/Ki.Math-JVM.git")
                    developerConnection.set("scm:git:ssh://github.com/kixi-io/Ki.Math-JVM.git")
                    url.set("https://github.com/kixi-io/Ki.Math-JVM/")
                }
            }
        }
    }
}
