plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(21)
    // The public API is a contract (AGENTS.md rule 4), checked against the dump in sdk/core/api/
    // (`./gradlew :sdk:core:updateKotlinAbi` rewrites it after an intentional change).
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    jvm()
    android {
        namespace = "dev.pedrorau.kuotakit.sdk.core"
        // OkHttp 5 (brought by the Ktor engine) needs to be compiled against API 37 or later.
        compileSdk = 37
        minSdk = 23
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The API models are the ones of the backend: one definition, in contracts (ADR 0004).
            api(project(":contracts"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies { implementation(libs.swagger.request.validator.core) }
    }
}
