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
 * The SDK core is shared by every platform and knows no store (ADR 0002): the billing libraries belong to the adapters,
 * and its common code uses only Kotlin, kotlinx and Ktor.
 */
object SdkRules {
    private val BILLING_LIBRARIES = listOf("com.android.billingclient", "StoreKit", "platform.StoreKit")
    private val PLATFORM_REFERENCE =
        Regex("""(^|[^\w.])(java|javax|android|androidx|com\.android|com\.google\.android|platform)\.[A-Za-z]""")

    /** No source of `sdk/core` mentions a billing library. */
    fun coreDoesNotKnowBillingLibraries(scope: KoScope) {
        scope.files
            .filter { it.path.contains("/sdk/core/src/") }
            .assertFalse(strict = true) { file -> BILLING_LIBRARIES.any { file.text.contains(it) } }
    }

    /** The common code of `sdk/core` does not reach for `java.*`, Android or Apple frameworks. */
    fun coreCommonCodeIsPlatformIndependent(scope: KoScope) {
        scope.files
            .filter { it.path.contains("/sdk/core/src/commonMain/") }
            .assertFalse(strict = true) { PLATFORM_REFERENCE.containsMatchIn(it.text) }
    }

    /** The Play client must attach the account id of the buyer to the purchase when it starts it (ADR 0017). */
    fun playClientAttachesTheAccountId(scope: KoScope) {
        scope.files
            .filter {
                it.path.endsWith(
                    "/sdk/android/src/main/kotlin/dev/pedrorau/kuotakit/sdk/android/BillingClientPlayClient.kt",
                )
            }.assertTrue(strict = true) { it.text.contains(".setObfuscatedAccountId(") }
    }

    /** The core does not depend on the Android module (a Gradle dependency would not show in a source file). */
    fun coreBuildDoesNotDependOnTheAndroidModule(buildScript: String) {
        check(!Regex("""project\(\s*["']:sdk:android["']\s*\)""").containsMatchIn(buildScript)) {
            "sdk/core/build.gradle.kts depends on :sdk:android: the core must not know an adapter (ADR 0002)"
        }
    }
}
