plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
    // Konsist reads the project's sources while the tests run, which Gradle cannot see: without declaring them as
    // inputs a violation would be hidden behind an up-to-date or cached test task.
    inputs
        .files(
            rootProject.fileTree(rootDir) {
                include("**/src/**/*.kt")
                exclude("**/build/**", "**/node_modules/**", "**/.gradle/**")
            },
        ).withPropertyName("analyzedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
