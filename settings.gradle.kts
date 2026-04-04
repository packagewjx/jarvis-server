plugins {
    // Allow Gradle toolchains to download missing JDKs (e.g. Java 17) from Foojay.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "jarvis-server"
include(":server")
