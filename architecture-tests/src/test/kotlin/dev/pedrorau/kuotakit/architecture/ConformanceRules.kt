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

/**
 * The conformance suite is black box (ADR 0007): it talks to the address of a backend and knows nothing of
 * its code. If it imported the backend it would stop proving that another backend, which
 * has no such code, behaves the same.
 */
object ConformanceRules {
    private const val BACKEND = "dev.pedrorau.kuotakit.backend"
    private val BACKEND_DEPENDENCY = Regex("""project\(\s*["']:backend["']\s*\)""")

    /** No source of `conformance/` mentions the backend's package, by import or fully qualified. */
    fun conformanceDoesNotImportTheBackend(scope: KoScope) {
        scope.files
            .filter { it.path.contains("/conformance/src/") }
            .assertFalse(strict = true) { it.text.contains(BACKEND) }
    }

    /** Its build script must not depend on the `:backend` project either. */
    fun conformanceBuildDoesNotDependOnTheBackend(buildScript: String) {
        check(!BACKEND_DEPENDENCY.containsMatchIn(buildScript)) {
            "conformance/build.gradle.kts depends on the :backend project: the suite must stay black box (ADR 0007)"
        }
    }
}
