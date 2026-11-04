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
    targetClasses.set(listOf("dev.pedrorau.kuotakit.backend.*"))
    junit5PluginVersion.set(libs.versions.pitest.junit5)
    outputFormats.set(listOf("HTML", "XML"))
}
