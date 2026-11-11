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
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import java.io.File
import java.nio.file.Files

private fun project(vararg files: Pair<String, String>) =
    Files.createTempDirectory("konsist-canary").let { root ->
        files.forEach { (relative, content) ->
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }
        Konsist.scopeFromExternalDirectory(root.toString())
    }

/** The rules that keep the conformance suite black box (ADR 0007), with their canaries. */
class ConformanceRulesTest :
    StringSpec({
        "the real suite does not know the backend" {
            ConformanceRules.conformanceDoesNotImportTheBackend(Konsist.scopeFromProduction())
            ConformanceRules.conformanceDoesNotImportTheBackend(Konsist.scopeFromTest())
        }

        "the real build script of the suite does not depend on the backend" {
            val script = File(System.getProperty("user.dir")).parentFile.resolve("conformance/build.gradle.kts")
            ConformanceRules.conformanceBuildDoesNotDependOnTheBackend(script.readText())
        }

        "canary: a conformance source that only knows contracts is accepted" {
            val scope =
                project(
                    "conformance/src/main/kotlin/Suite.kt" to
                        "package dev.pedrorau.kuotakit.conformance\n\n" +
                        "import dev.pedrorau.kuotakit.contracts.ErrorCode\n",
                )
            shouldNotThrowAny { ConformanceRules.conformanceDoesNotImportTheBackend(scope) }
        }

        mapOf(
            "an import" to "import dev.pedrorau.kuotakit.backend.domain.Purchase\n",
            "a fully qualified use" to "val x: dev.pedrorau.kuotakit.backend.web.ApiKeys? = null\n",
        ).forEach { (what, line) ->
            "canary: $what of the backend in the suite is reported" {
                val scope =
                    project(
                        "conformance/src/main/kotlin/Suite.kt" to "package dev.pedrorau.kuotakit.conformance\n\n$line",
                    )
                shouldThrowAny { ConformanceRules.conformanceDoesNotImportTheBackend(scope) }
            }
        }

        "canary: a test of the suite that imports the backend is reported" {
            val scope =
                project(
                    "conformance/src/test/kotlin/SuiteTest.kt" to
                        "package dev.pedrorau.kuotakit.conformance\n\nimport dev.pedrorau.kuotakit.backend.Main\n",
                )
            shouldThrowAny { ConformanceRules.conformanceDoesNotImportTheBackend(scope) }
        }

        "canary: a build script that depends on :backend is reported, one that depends on contracts is not" {
            shouldNotThrowAny {
                ConformanceRules.conformanceBuildDoesNotDependOnTheBackend("""implementation(project(":contracts"))""")
            }
            listOf(
                """implementation(project(":backend"))""",
                """testImplementation(project( ':backend' ))""",
            ).forEach { line ->
                shouldThrowAny { ConformanceRules.conformanceBuildDoesNotDependOnTheBackend(line) }
            }
        }
    })
