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

/**
 * A rule that silently stops working is worse than no rule (a detekt rule once did exactly that here). These
 * tests run every rule against small throwaway projects: one that breaks it and one that respects it.
 */
class ArchitectureRulesCanaryTest :
    StringSpec({
        fun project(vararg files: Pair<String, String>): KoScope {
            val root: Path = Files.createTempDirectory("konsist-canary")
            files.forEach { (relative, content) ->
                val file = root.resolve(relative)
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            }
            return Konsist.scopeFromExternalDirectory(root.toString())
        }

        val serializableClass =
            "import kotlinx.serialization.Serializable\n\n@Serializable\nclass Model(val id: String)\n"

        "a @Serializable type inside contracts is accepted" {
            val scope =
                project(
                    "contracts/src/commonMain/kotlin/Model.kt" to serializableClass,
                    "backend/src/main/kotlin/Clean.kt" to "class Clean\n",
                )
            shouldNotThrowAny { ArchitectureRules.serializableTypesLiveOnlyInContracts(scope) }
        }

        // Every way of using the annotation outside contracts must be reported, not only a plain class.
        mapOf(
            "a class" to serializableClass,
            "an object" to "import kotlinx.serialization.Serializable\n\n@Serializable\nobject Model\n",
            "an interface" to "import kotlinx.serialization.Serializable\n\n@Serializable\ninterface Model\n",
            "an import alias" to
                "import kotlinx.serialization.Serializable as S\n\n@S\nclass Model(val id: String)\n",
            "a fully qualified use" to "@kotlinx.serialization.Serializable\nclass Model(val id: String)\n",
        ).forEach { (what, source) ->
            "@Serializable on $what outside contracts is reported" {
                val scope = project("backend/src/main/kotlin/Model.kt" to source)
                shouldThrowAny { ArchitectureRules.serializableTypesLiveOnlyInContracts(scope) }
            }
        }

        val contractsFile = "contracts/src/commonMain/kotlin/Model.kt"

        "contracts code that imports only kotlin and kotlinx is accepted" {
            val scope =
                project(
                    contractsFile to
                        "import kotlin.time.Instant\nimport kotlinx.serialization.SerialName\n\nclass Model\n",
                )
            shouldNotThrowAny { ArchitectureRules.contractsCommonCodeIsPlatformIndependent(scope) }
        }

        mapOf(
            "java.time" to "import java.time.Instant",
            "android" to "import android.os.Build",
            "okhttp" to "import okhttp3.OkHttpClient",
        ).forEach { (what, import) ->
            "contracts code that imports $what is reported" {
                val scope = project(contractsFile to "$import\n\nclass Model\n")
                shouldThrowAny { ArchitectureRules.contractsCommonCodeIsPlatformIndependent(scope) }
            }
        }

        "contracts code that uses a platform type with its full name and no import is reported" {
            val scope =
                project(
                    contractsFile to "import kotlin.time.Instant\n\nval status: io.ktor.http.HttpStatusCode? = null\n",
                )
            shouldThrowAny { ArchitectureRules.contractsCommonCodeIsPlatformIndependent(scope) }
        }

        "a comment that merely says Java does not trip the contracts rule" {
            val scope =
                project(contractsFile to "import kotlin.time.Instant\n\n/** Not the Java way. */\nclass Model\n")
            shouldNotThrowAny { ArchitectureRules.contractsCommonCodeIsPlatformIndependent(scope) }
        }

        val exposedImport = "import org.jetbrains.exposed.sql.Table\n\nclass Repo\n"
        val web = "backend/src/main/kotlin/web/Handler.kt"

        "using the persistence libraries inside the storage package is accepted" {
            val scope =
                project(
                    "backend/src/main/kotlin/storage/Repo.kt" to
                        "package dev.pedrorau.kuotakit.backend.storage\n\n$exposedImport",
                    "backend/src/main/kotlin/storage/postgres/Impl.kt" to
                        "package dev.pedrorau.kuotakit.backend.storage.postgres\n\n$exposedImport",
                    web to "package dev.pedrorau.kuotakit.backend.web\n\nclass Handler\n",
                )
            shouldNotThrowAny { ArchitectureRules.persistenceLibrariesAreUsedOnlyByTheStoragePackage(scope) }
        }

        mapOf(
            "in another package" to "package dev.pedrorau.kuotakit.backend.web\n\n$exposedImport",
            "in a look-alike package" to "package dev.pedrorau.kuotakit.backend.storagex\n\n$exposedImport",
            "without an import" to
                "package dev.pedrorau.kuotakit.backend.web\n\nval table: org.jetbrains.exposed.sql.Table? = null\n",
        ).forEach { (what, source) ->
            "using Exposed $what is reported" {
                val scope = project(web to source)
                shouldThrowAny { ArchitectureRules.persistenceLibrariesAreUsedOnlyByTheStoragePackage(scope) }
            }
        }

        mapOf(
            "Flyway" to "import org.flywaydb.core.Flyway",
            "the connection pool" to "import com.zaxxer.hikari.HikariDataSource",
            "the JDBC driver" to "import org.postgresql.Driver",
            "java.sql" to "import java.sql.Connection",
        ).forEach { (what, import) ->
            "using $what outside the storage package is reported" {
                val scope = project(web to "package dev.pedrorau.kuotakit.backend.web\n\n$import\n\nclass Handler\n")
                shouldThrowAny { ArchitectureRules.persistenceLibrariesAreUsedOnlyByTheStoragePackage(scope) }
            }
        }

        val domainFile = "backend/src/main/kotlin/domain/Calculator.kt"

        "a sub-package of the domain is checked too" {
            val scope =
                project(
                    "backend/src/main/kotlin/domain/sub/Deep.kt" to
                        "package dev.pedrorau.kuotakit.backend.domain.sub\n\n" +
                        "import io.ktor.http.HttpStatusCode\n\nclass Deep\n",
                )
            shouldThrowAny { ArchitectureRules.domainDoesNotDependOnTheWebOrStorage(scope) }
        }

        "a look-alike package is not the domain" {
            val scope =
                project(
                    "backend/src/main/kotlin/domainx/Other.kt" to
                        "package dev.pedrorau.kuotakit.backend.domainx\n\n" +
                        "import io.ktor.http.HttpStatusCode\n\nclass Other\n",
                    domainFile to "package dev.pedrorau.kuotakit.backend.domain\n\nclass Calculator\n",
                )
            shouldNotThrowAny { ArchitectureRules.domainDoesNotDependOnTheWebOrStorage(scope) }
        }

        "a domain that depends only on Kotlin is accepted" {
            val scope =
                project(
                    domainFile to
                        "package dev.pedrorau.kuotakit.backend.domain\n\n" +
                        "import kotlin.time.Instant\n\nclass Calculator\n",
                )
            shouldNotThrowAny { ArchitectureRules.domainDoesNotDependOnTheWebOrStorage(scope) }
        }

        mapOf(
            "Ktor" to "import io.ktor.server.application.Application",
            "the storage package" to "import dev.pedrorau.kuotakit.backend.storage.PostgresStorage",
            "the web package" to "import dev.pedrorau.kuotakit.backend.web.ApiKeys",
            "the configuration package" to "import dev.pedrorau.kuotakit.backend.config.AppConfig",
            "SnakeYAML" to "import org.snakeyaml.engine.v2.api.Load",
        ).forEach { (what, import) ->
            "a domain that depends on $what is reported" {
                val scope =
                    project(
                        domainFile to "package dev.pedrorau.kuotakit.backend.domain\n\n$import\n\nclass Calculator\n",
                    )
                shouldThrowAny { ArchitectureRules.domainDoesNotDependOnTheWebOrStorage(scope) }
            }
        }
    })
