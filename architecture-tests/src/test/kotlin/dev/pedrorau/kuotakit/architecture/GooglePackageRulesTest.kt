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

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import java.nio.file.Files
import java.nio.file.Path

private fun project(vararg files: Pair<String, String>): KoScope {
    val root: Path = Files.createTempDirectory("konsist-canary")
    files.forEach { (relative, content) ->
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }
    return Konsist.scopeFromExternalDirectory(root.toString())
}

private const val GOOGLE_IMPORT = "import com.google.api.services.androidpublisher.AndroidPublisher\n\nclass Api\n"

/** The rules that keep Google's libraries inside `backend.google` (ADR 0013), with their canaries. */
class GooglePackageRulesTest :
    StringSpec({
        "the real project keeps Google's libraries in the google package" {
            ArchitectureRules.googleLibrariesAreUsedOnlyByTheGooglePackage(Konsist.scopeFromProduction())
        }

        "the real domain does not know the google package nor Google's libraries" {
            ArchitectureRules.domainDoesNotDependOnTheWebOrStorage(Konsist.scopeFromProduction())
        }

        "canary: using Google's libraries inside the google package is accepted" {
            val scope =
                project(
                    "backend/src/main/kotlin/google/Api.kt" to
                        "package dev.pedrorau.kuotakit.backend.google\n\n$GOOGLE_IMPORT",
                    "backend/src/main/kotlin/google/deep/Api.kt" to
                        "package dev.pedrorau.kuotakit.backend.google.deep\n\n$GOOGLE_IMPORT",
                    "backend/src/main/kotlin/web/Handler.kt" to
                        "package dev.pedrorau.kuotakit.backend.web\n\nclass Handler\n",
                )
            shouldNotThrowAny { ArchitectureRules.googleLibrariesAreUsedOnlyByTheGooglePackage(scope) }
        }

        mapOf(
            "the domain" to "package dev.pedrorau.kuotakit.backend.domain\n\n$GOOGLE_IMPORT",
            "the web package" to "package dev.pedrorau.kuotakit.backend.web\n\n$GOOGLE_IMPORT",
            "a look-alike package" to "package dev.pedrorau.kuotakit.backend.googlex\n\n$GOOGLE_IMPORT",
            "a use with no import" to
                "package dev.pedrorau.kuotakit.backend.web\n\n" +
                "val api: com.google.auth.oauth2.GoogleCredentials? = null\n",
        ).forEach { (where, source) ->
            "canary: Google's libraries in $where are reported" {
                val scope = project("backend/src/main/kotlin/Other.kt" to source)
                shouldThrowAny { ArchitectureRules.googleLibrariesAreUsedOnlyByTheGooglePackage(scope) }
            }
        }

        mapOf(
            "the google package" to "import dev.pedrorau.kuotakit.backend.google.GooglePlayStoreApi",
            "Google's libraries" to "import com.google.auth.oauth2.GoogleCredentials",
        ).forEach { (what, import) ->
            "canary: the domain importing $what is reported" {
                val scope =
                    project(
                        "backend/src/main/kotlin/domain/Service.kt" to
                            "package dev.pedrorau.kuotakit.backend.domain\n\n$import\n\nclass Service\n",
                    )
                shouldThrowAny { ArchitectureRules.domainDoesNotDependOnTheWebOrStorage(scope) }
            }
        }
    })
