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

private const val APP = "examples/android/src/main/kotlin/dev/pedrorau/kuotakit/sandbox/A.kt"

/** The rules that keep the example app on the public API of the SDK, with their canaries. */
class ExampleAppRulesTest :
    StringSpec({
        "the real example app uses only the public SDK and has no billing library and no secret key" {
            ExampleAppRules.usesOnlyThePublicSdk(Konsist.scopeFromProduction())
            ExampleAppRules.hasNoBillingLibraryAndNoSecretKey(Konsist.scopeFromProduction())
        }

        "canary: an app that uses the core, the models, its own code and configure is accepted" {
            val scope =
                project(
                    APP to
                        """
                        package x

                        import dev.pedrorau.kuotakit.contracts.CustomerInfo
                        import dev.pedrorau.kuotakit.sandbox.ScreenState
                        import dev.pedrorau.kuotakit.sdk.KuotaKit
                        import dev.pedrorau.kuotakit.sdk.android.configure
                        """.trimIndent(),
                )
            shouldNotThrowAny {
                ExampleAppRules.usesOnlyThePublicSdk(scope)
                ExampleAppRules.hasNoBillingLibraryAndNoSecretKey(scope)
            }
        }

        mapOf(
            "an adapter class of the Android SDK" to "import dev.pedrorau.kuotakit.sdk.android.PlayBilling\n",
            "a wildcard import of the Android SDK" to "import dev.pedrorau.kuotakit.sdk.android.*\n",
            "a fully qualified use of the Android SDK" to
                "val x = dev.pedrorau.kuotakit.sdk.android.PlayBilling::class\n",
            "the backend" to "import dev.pedrorau.kuotakit.backend.domain.Purchase\n",
            "the conformance suite" to "import dev.pedrorau.kuotakit.conformance.Backend\n",
        ).forEach { (what, line) ->
            "canary: an import of $what is reported" {
                shouldThrowAny { ExampleAppRules.usesOnlyThePublicSdk(project(APP to "package x\n\n$line")) }
            }
        }

        mapOf(
            "Play Billing" to "import com.android.billingclient.api.BillingClient\n",
            "a fully qualified use of Play Billing" to "val x = com.android.billingclient.api.BillingClient::class\n",
            "a secret key" to "val key = \"sk_0123456789abcdef\"\n",
        ).forEach { (what, line) ->
            "canary: $what in the app is reported" {
                shouldThrowAny {
                    ExampleAppRules.hasNoBillingLibraryAndNoSecretKey(
                        project(APP to "package x\n\n$line"),
                    )
                }
            }
        }
    })
