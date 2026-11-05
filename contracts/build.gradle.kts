plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(21)
    // The public API is a contract (AGENTS.md rule 4): every public symbol must be declared explicitly...
    explicitApi()
    // ...and any change to it must be visible in review: `check` compares it with the dump in contracts/api/
    // (`./gradlew :contracts:updateKotlinAbi` rewrites it after an intentional change).
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    jvm()
    android {
        namespace = "dev.pedrorau.kuotakit.contracts"
        compileSdk = 36
        minSdk = 23
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
