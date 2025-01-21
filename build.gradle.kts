plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "io.github"
version = "1.2-Stable"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

intellij {
    version.set("2024.1.7")
    plugins.set(listOf("java", "JUnit", "com.intellij.java"))
}

tasks {
    buildSearchableOptions {
        enabled = false
    }

    patchPluginXml {
        version.set("${project.version}")
        sinceBuild.set("241")
        untilBuild.set("251.*")
    }
}