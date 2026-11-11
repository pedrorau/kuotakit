plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The SDK as an app uses it, against a backend that runs in Docker: no backend code is imported (it is black box).
    testImplementation(project(":sdk:core"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

// The tests need the backend and the fake Google of docker-compose.conformance.yml up (docs/sdk-android.md), so they
// are not part of `./gradlew build`: they run with `./gradlew :sdk:e2e:e2eTest`.
tasks.test {
    enabled = false
}

tasks.register<Test>("e2eTest") {
    description = "Runs the SDK against the reference backend and the fake Google (needs Docker Compose up)"
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    // Always run: the world outside Gradle (the containers) is not an input it can see.
    outputs.upToDateWhen { false }
    // The wiring is not a secret and has defaults; a Colima or remote engine may need other addresses.
    listOf("KUOTAKIT_E2E_BACKEND_URL", "KUOTAKIT_E2E_CONTROL_URL").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
