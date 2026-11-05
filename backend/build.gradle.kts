plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.pitest)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.pedrorau.kuotakit.backend.MainKt")
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.pitest)
}

tasks.test {
    useJUnitPlatform()
}

pitest {
    // `-PpitestTargets=pkg.A*,pkg.B*` limits the run to some classes (CI mutates only the code a PR changed).
    targetClasses.set(
        providers
            .gradleProperty("pitestTargets")
            .map {
                it.split(",").toSet()
            }.orElse(setOf("dev.pedrorau.kuotakit.backend.*")),
    )
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    outputFormats.set(listOf("HTML", "XML"))
    failWhenNoMutations.set(false)
    // Kotlin generates bytecode that has no logic of its own; mutating it only produces noise.
    avoidCallsTo.set(setOf("kotlin.jvm.internal", "kotlinx.coroutines"))
    excludedMethods.set(setOf("component*", "copy", "hashCode", "equals", "toString", "*\$default"))
    excludedClasses.set(setOf("*\$Companion", "*\$WhenMappings", "*\$\$serializer"))
    // No mutationThreshold yet: it is switched on (70 % for the critical modules, ADR 0011) together with the
    // first real domain logic, the entitlement computation. Until then the reports are informative only.
}
