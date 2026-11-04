plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(21)
    // The public API is a contract (AGENTS.md rule 4): every public symbol must be declared explicitly.
    explicitApi()

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
            api(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
