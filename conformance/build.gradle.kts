plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.pedrorau.kuotakit.conformance.MainKt")
}

dependencies {
    // Black box: the suite knows nothing of the backend, not even its shared models (a Konsist rule checks it).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.swagger.request.validator.core)
    implementation(libs.logback.classic)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

// The contract (generated from the models) and the manifest travel inside the jar, so the image needs nothing else.
tasks.processResources {
    from(rootProject.layout.projectDirectory.file("docs/api/openapi.json")) { into("contract") }
    from(layout.projectDirectory.file("manifest.txt")) { into("conformance") }
}

tasks.test {
    useJUnitPlatform()
}
