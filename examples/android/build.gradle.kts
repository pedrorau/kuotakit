import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is the maintainer's usual one: a keystore.properties at the repository root (git-ignored) with
// storeFile, storePassword, keyAlias and keyPassword. It is configured only when that file exists, so a fresh clone and
// CI still build debug.
val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

// The defaults of the app: sandbox.properties (committed), overridden by `sandbox.*` keys of the root local.properties
// and, above both, by `-Psandbox.baseUrl=...` / `-Psandbox.apiKey=...` on the command line.
fun load(file: File) = Properties().apply { if (file.exists()) FileInputStream(file).use(::load) }

val sandboxDefaults = load(file("sandbox.properties"))
val localProperties = load(rootProject.file("local.properties"))

fun sandboxSetting(name: String): String =
    providers.gradleProperty("sandbox.$name").orNull
        ?: localProperties.getProperty("sandbox.$name")
        ?: sandboxDefaults.getProperty(name, "")

// A value written as a Java string literal, so a quote or a backslash in it cannot break the generated BuildConfig.
fun javaString(value: String): String =
    "\"" +
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "") + "\""

fun keystoreSetting(name: String): String =
    requireNotNull(keystoreProperties.getProperty(name)) {
        "keystore.properties has no `$name` (see keystore.properties.example)"
    }

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "dev.pedrorau.kuotakit.sandbox"
    // OkHttp 5 (brought by the Ktor engine of the SDK) needs to be compiled against API 37 or later.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pedrorau.kuotakit.sandbox"
        // 24, not the 23 of the SDK: Play automatic protection refuses a bundle with a lower minSdk when it uploads.
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
        buildConfigField("String", "DEFAULT_BASE_URL", javaString(sandboxSetting("baseUrl")))
        buildConfigField("String", "DEFAULT_API_KEY", javaString(sandboxSetting("apiKey")))
        buildConfigField("String", "PRIVACY_POLICY_URL", javaString(sandboxSetting("privacyPolicyUrl")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = File(rootDir, keystoreSetting("storeFile"))
                storePassword = keystoreSetting("storePassword")
                keyAlias = keystoreSetting("keyAlias")
                keyPassword = keystoreSetting("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":sdk:android"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
