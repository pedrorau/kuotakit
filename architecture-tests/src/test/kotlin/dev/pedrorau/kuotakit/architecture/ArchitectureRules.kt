/*
 * Copyright 2026 Pedro Rau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.pedrorau.kuotakit.architecture

import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue

/**
 * Architecture rules of the project (ADR 0011). Each rule takes the code to check, so the real project must
 * pass it and [ArchitectureRulesCanaryTest] proves it still fails on a violation. `strict = true` makes a rule
 * fail when it matches nothing, so a rename cannot turn a rule into a check that silently passes.
 *
 * The rules look at the text of each file instead of at declarations: that also catches `@Serializable` on an
 * object or an interface, an import alias (`import ... Serializable as S`) and a fully qualified use without
 * any import.
 */
object ArchitectureRules {
    private const val STORAGE_PACKAGE = "dev.pedrorau.kuotakit.backend.storage"
    private const val SERIALIZABLE = "kotlinx.serialization.Serializable"
    private const val DOMAIN_PACKAGE = "dev.pedrorau.kuotakit.backend.domain"
    private const val GOOGLE_PACKAGE = "dev.pedrorau.kuotakit.backend.google"
    private val PERSISTENCE_LIBRARIES =
        listOf("org.jetbrains.exposed", "org.flywaydb", "com.zaxxer.hikari", "org.postgresql", "java.sql", "javax.sql")
    private val NOT_FOR_THE_DOMAIN =
        listOf(
            "io.ktor",
            "org.snakeyaml",
            "dev.pedrorau.kuotakit.backend.storage",
            "dev.pedrorau.kuotakit.backend.web",
            "dev.pedrorau.kuotakit.backend.config",
            "dev.pedrorau.kuotakit.backend.google",
            "com.google",
        )
    private val PLATFORM_REFERENCE = Regex("""(^|[^\w.])(java|javax|android|androidx|io\.ktor|okhttp3)\.[a-z]""")

    /** API models are defined only in `contracts/` (ADR 0004): nothing else may use `@Serializable`. */
    fun serializableTypesLiveOnlyInContracts(scope: KoScope) {
        scope.files
            .filterNot { it.path.contains("/contracts/src/") }
            .assertFalse(strict = true) { it.text.contains(SERIALIZABLE) }
    }

    /**
     * The backend talks to the database only through its storage package (ADR 0009): Exposed, Flyway, the connection
     * pool and JDBC itself stay there.
     */
    fun persistenceLibrariesAreUsedOnlyByTheStoragePackage(scope: KoScope) {
        scope.files
            .filterNot { it.isInBackendStoragePackage() }
            .assertFalse(strict = true) { file -> PERSISTENCE_LIBRARIES.any { file.text.contains(it) } }
    }

    /**
     * Google's client libraries stay in the `google` package of the backend: the rest of the code sees Google Play only
     * through the `PlayStoreApi` interface of the domain, so it can be faked and swapped.
     */
    fun googleLibrariesAreUsedOnlyByTheGooglePackage(scope: KoScope) {
        scope.files
            .filterNot { it.isInBackendGooglePackage() }
            .assertFalse(strict = true) { it.text.contains("com.google.") }
    }

    /** The domain is plain Kotlin: it knows neither the web framework nor how things are stored (ADR 0009). */
    fun domainDoesNotDependOnTheWebOrStorage(scope: KoScope) {
        scope.files
            .filter { it.isInBackendDomainPackage() }
            .assertFalse(strict = true) { file -> NOT_FOR_THE_DOMAIN.any { file.text.contains(it) } }
    }

    /**
     * `contracts/` is shared by the backend and by every SDK platform, so its common code may only use Kotlin and
     * kotlinx: no `java.*`, no `android.*`, no platform library (ADR 0002, ADR 0004).
     */
    fun contractsCommonCodeIsPlatformIndependent(scope: KoScope) {
        scope.files
            .filter { it.path.contains("/contracts/src/commonMain/") }
            .assertTrue(strict = true) { file ->
                val imports = file.imports.all { it.name.startsWith("kotlin.") || it.name.startsWith("kotlinx.") }
                // Also a fully qualified use without an import, such as `io.ktor.http.HttpStatusCode`.
                imports && !PLATFORM_REFERENCE.containsMatchIn(file.text)
            }
    }

    /** True for `...backend.domain` and its sub-packages. */
    private fun com.lemonappdev.konsist.api.declaration.KoFileDeclaration.isInBackendDomainPackage(): Boolean {
        val name = packagee?.name ?: return false
        return path.contains("/backend/src/") && (name == DOMAIN_PACKAGE || name.startsWith("$DOMAIN_PACKAGE."))
    }

    /** True for `...backend.google` and its sub-packages. */
    private fun com.lemonappdev.konsist.api.declaration.KoFileDeclaration.isInBackendGooglePackage(): Boolean {
        val name = packagee?.name ?: return false
        return path.contains("/backend/src/") && (name == GOOGLE_PACKAGE || name.startsWith("$GOOGLE_PACKAGE."))
    }

    /** True for `...backend.storage` and its sub-packages, but not for a look-alike such as `...backend.storagex`. */
    private fun com.lemonappdev.konsist.api.declaration.KoFileDeclaration.isInBackendStoragePackage(): Boolean {
        val name = packagee?.name ?: return false
        return path.contains("/backend/src/") && (name == STORAGE_PACKAGE || name.startsWith("$STORAGE_PACKAGE."))
    }
}
