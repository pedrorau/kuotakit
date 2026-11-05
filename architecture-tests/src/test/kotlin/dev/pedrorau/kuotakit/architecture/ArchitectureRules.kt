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
    private const val EXPOSED = "org.jetbrains.exposed"

    /** API models are defined only in `contracts/` (ADR 0004): nothing else may use `@Serializable`. */
    fun serializableTypesLiveOnlyInContracts(scope: KoScope) {
        scope.files
            .filterNot { it.path.contains("/contracts/src/") }
            .assertFalse(strict = true) { it.text.contains(SERIALIZABLE) }
    }

    /** The backend talks to the database only through its storage package (ADR 0009). */
    fun exposedIsUsedOnlyByTheStoragePackage(scope: KoScope) {
        scope.files
            .filterNot { it.isInBackendStoragePackage() }
            .assertFalse(strict = true) { it.text.contains(EXPOSED) }
    }

    /** True for `...backend.storage` and its sub-packages, but not for a look-alike such as `...backend.storagex`. */
    private fun com.lemonappdev.konsist.api.declaration.KoFileDeclaration.isInBackendStoragePackage(): Boolean {
        val name = packagee?.name ?: return false
        return path.contains("/backend/src/") && (name == STORAGE_PACKAGE || name.startsWith("$STORAGE_PACKAGE."))
    }
}
