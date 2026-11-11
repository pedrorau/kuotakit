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

private const val CORE = "sdk/core/src/commonMain/kotlin/Core.kt"

/** The rules that keep the SDK core free of stores and platforms (ADR 0002), with their canaries. */
class SdkRulesTest :
    StringSpec({
        "the real SDK core knows no billing library and no platform in its common code" {
            SdkRules.coreDoesNotKnowBillingLibraries(Konsist.scopeFromProduction())
            SdkRules.coreCommonCodeIsPlatformIndependent(Konsist.scopeFromProduction())
        }

        "canary: a core that only uses Kotlin and Ktor is accepted" {
            val scope = project(CORE to "package x\n\nimport io.ktor.client.HttpClient\nimport kotlin.time.Instant\n")
            shouldNotThrowAny {
                SdkRules.coreDoesNotKnowBillingLibraries(scope)
                SdkRules.coreCommonCodeIsPlatformIndependent(scope)
            }
        }

        mapOf(
            "an import of Play Billing" to "import com.android.billingclient.api.BillingClient\n",
            "a use of StoreKit" to "val x: platform.StoreKit.SKProduct? = null\n",
        ).forEach { (what, line) ->
            "canary: $what in any source of the core is reported" {
                listOf("commonMain", "androidMain", "iosMain").forEach { set ->
                    val scope = project("sdk/core/src/$set/kotlin/Core.kt" to "package x\n\n$line")
                    shouldThrowAny { SdkRules.coreDoesNotKnowBillingLibraries(scope) }
                }
            }
        }

        mapOf(
            "java" to "import java.util.UUID\n",
            "Android" to "import android.content.Context\n",
            "Apple frameworks" to "import platform.Foundation.NSUUID\n",
            "a fully qualified use" to "val x: java.io.File? = null\n",
        ).forEach { (what, line) ->
            "canary: $what in the common code is reported" {
                val scope = project(CORE to "package x\n\n$line")
                shouldThrowAny { SdkRules.coreCommonCodeIsPlatformIndependent(scope) }
            }
        }

        "canary: platform code in androidMain may use Android, only the common code may not" {
            val scope =
                project(
                    CORE to "package x\n",
                    "sdk/core/src/androidMain/kotlin/A.kt" to "package x\n\nimport android.content.Context\n",
                )
            shouldNotThrowAny { SdkRules.coreCommonCodeIsPlatformIndependent(scope) }
        }

        "the real Play client sets the obfuscated account id, and the real core does not depend on the Android module" {
            SdkRules.playClientAttachesTheAccountId(Konsist.scopeFromProduction())
            val script =
                java.io
                    .File(System.getProperty("user.dir"))
                    .parentFile
                    .resolve("sdk/core/build.gradle.kts")
            SdkRules.coreBuildDoesNotDependOnTheAndroidModule(script.readText())
        }

        "canary: a Play client that does not set the account id is reported, one that does is not" {
            val path = "sdk/android/src/main/kotlin/dev/pedrorau/kuotakit/sdk/android/BillingClientPlayClient.kt"
            shouldNotThrowAny {
                SdkRules.playClientAttachesTheAccountId(
                    project(path to "val p = BillingFlowParams.newBuilder().setObfuscatedAccountId(id)\n"),
                )
            }
            shouldThrowAny {
                SdkRules.playClientAttachesTheAccountId(
                    project(path to "val p = BillingFlowParams.newBuilder().build()\n"),
                )
            }
        }

        "canary: a core build script that depends on :sdk:android is reported" {
            shouldNotThrowAny { SdkRules.coreBuildDoesNotDependOnTheAndroidModule("""api(project(":contracts"))""") }
            shouldThrowAny {
                SdkRules.coreBuildDoesNotDependOnTheAndroidModule(
                    """implementation(project(":sdk:android"))""",
                )
            }
        }
    })
