plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.pitest) apply false
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(rootProject.libs.versions.ktlint.engine)
    }

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    }

    // By default `check` only runs the plain `detekt` task, which has no type resolution: rules such as
    // UnsafeCallOnNullableType (`!!`) silently do nothing there, and in Kotlin Multiplatform modules it has
    // no sources at all. Wire in the type-resolution tasks (detektMain, detektMainJvm, ...) and the per
    // source set tasks (they cover iOS-only code) so the rules really apply.
    val detektTasks = Regex("detekt(Main|Test)(Jvm|Android)?|detekt(?!Baseline).+SourceSet")
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(tasks.matching { it.name.matches(detektTasks) })
    }
}
