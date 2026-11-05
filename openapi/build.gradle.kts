plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) // only for the @Serializable fixtures of the tests
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.swagger.request.validator.core)
}

tasks.test {
    useJUnitPlatform()
}

// The generated OpenAPI document is committed in docs/api/openapi.json. `check` fails when it is out of date with the
// models and the endpoint table, exactly like the public API dump of `contracts`.
val specFile = rootProject.layout.projectDirectory.file("docs/api/openapi.json")
val generatorMainClass = "dev.pedrorau.kuotakit.openapi.MainKt"

fun openApiTask(
    name: String,
    description: String,
    vararg arguments: String,
) = tasks.register<JavaExec>(name) {
    group = "openapi"
    this.description = description
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(generatorMainClass)
    workingDir = rootProject.projectDir
    args(*arguments)
}

openApiTask(
    "generateOpenApi",
    "Writes the generated OpenAPI document to build/openapi/openapi.json",
    "openapi/build/openapi/openapi.json",
)
openApiTask("updateOpenApi", "Rewrites docs/api/openapi.json after an intentional API change", "docs/api/openapi.json")
val checkOpenApi =
    openApiTask("checkOpenApi", "Fails when docs/api/openapi.json is out of date", "--check", "docs/api/openapi.json")
checkOpenApi.configure { inputs.file(specFile) }
tasks.named("check") { dependsOn(checkOpenApi) }
