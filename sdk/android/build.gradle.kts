plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

android {
    namespace = "dev.pedrorau.kuotakit.sdk.android"
    // OkHttp 5 (brought by the Ktor engine) needs to be compiled against API 37 or later.
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":sdk:core"))
    implementation(libs.play.billing.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
