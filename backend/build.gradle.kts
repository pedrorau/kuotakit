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
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback.classic)
    implementation(libs.snakeyaml.engine)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql.driver)
    implementation(libs.hikaricp)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.pitest)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.swagger.request.validator.core)
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
            }.orElse(setOf("dev.pedrorau.kuotakit.backend.domain.*")),
    )
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    outputFormats.set(listOf("HTML", "XML"))
    failWhenNoMutations.set(false)
    // Kotlin generates bytecode that has no logic of its own; mutating it only produces noise.
    avoidCallsTo.set(setOf("kotlin.jvm.internal", "kotlinx.coroutines"))
    excludedMethods.set(setOf("component*", "copy", "hashCode", "equals", "toString", "*\$default"))
    excludedClasses.set(setOf("*\$Companion", "*\$WhenMappings", "*\$\$serializer"))
    // The critical logic, the entitlement computation in the `domain` package, must keep a mutation score of at least
    // 70 % (ADR 0011); raise it over time. Storage needs Docker and the web layer is thin, so they are not gated.
    mutationThreshold.set(70)
}
