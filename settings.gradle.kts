pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads the JDK requested by the toolchain (JDK 21) when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kuotakit"

// Future bridges (bridges/react-native, bridges/flutter) keep their own build tools
// and are NOT included here (see AGENTS.md).
include(":contracts", ":backend", ":openapi", ":architecture-tests", ":conformance", ":sdk:core", ":sdk:android")
