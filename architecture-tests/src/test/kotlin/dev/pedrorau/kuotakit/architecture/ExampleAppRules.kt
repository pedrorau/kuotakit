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
import com.lemonappdev.konsist.api.declaration.KoImportDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse

/**
 * The example app is an example of the boundary the SDK draws: it uses only the public API of the SDK and its models
 * (`dev.pedrorau.kuotakit.sdk` and `dev.pedrorau.kuotakit.contracts`, plus the one function that configures the Android
 * SDK), talks to no billing library, and has no secret key in it.
 */
object ExampleAppRules {
    private const val MAIN = "/examples/android/src/main/"
    private val ALLOWED_PREFIXES =
        listOf(
            "dev.pedrorau.kuotakit.sandbox.",
            "dev.pedrorau.kuotakit.sdk.android.configure",
            "dev.pedrorau.kuotakit.contracts.",
        )
    private val SECRET_KEY = Regex("""\bsk_[A-Za-z0-9]""")

    /** The name of an import; Konsist leaves the `.*` of a wildcard import out of `name`, which would hide it. */
    private fun fullName(import: KoImportDeclaration) = if (import.isWildcard) import.name + ".*" else import.name

    /** The lines of a source that are not `package` or `import` ones: a fully qualified name there is a use too. */
    private fun codeLines(text: String) =
        text.lineSequence().filterNot { it.trimStart().startsWith("import ") || it.trimStart().startsWith("package ") }

    /** Every import of KuotaKit in the app is the core SDK, the models, the app's own code or `configure`. */
    fun usesOnlyThePublicSdk(scope: KoScope) {
        scope.files
            .filter { it.path.contains(MAIN) }
            .assertFalse(strict = true) { file ->
                file.imports.any { import ->
                    val name = fullName(import)
                    name.startsWith("dev.pedrorau.kuotakit.") &&
                        !name.startsWith("dev.pedrorau.kuotakit.sdk.") &&
                        ALLOWED_PREFIXES.none { name.startsWith(it) }
                } ||
                    file.imports.any {
                        fullName(it).startsWith("dev.pedrorau.kuotakit.sdk.android.") &&
                            fullName(it) != "dev.pedrorau.kuotakit.sdk.android.configure"
                    } ||
                    codeLines(file.text).any { "dev.pedrorau.kuotakit.sdk.android." in it }
            }
    }

    /** No billing library is used directly, and no secret key (`sk_...`) is written in a source of the app. */
    fun hasNoBillingLibraryAndNoSecretKey(scope: KoScope) {
        scope.files
            .filter { it.path.contains(MAIN) }
            .assertFalse(strict = true) { file ->
                file.imports.any { it.name.startsWith("com.android.billingclient") } ||
                    codeLines(file.text).any { "com.android.billingclient" in it } ||
                    SECRET_KEY.containsMatchIn(file.text)
            }
    }
}
