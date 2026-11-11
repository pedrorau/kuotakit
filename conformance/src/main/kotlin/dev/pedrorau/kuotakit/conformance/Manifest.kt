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

package dev.pedrorau.kuotakit.conformance

/** `conformance/manifest.txt`, the list of what the suite promises, which travels in the jar. */
internal object Manifest {
    private const val RESOURCE = "/conformance/manifest.txt"

    fun ids(): List<String> =
        (Manifest::class.java.getResourceAsStream(RESOURCE) ?: error("The manifest is not in the jar"))
            .use { it.readBytes().decodeToString() }
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore(' ') }

    /** The IDs of the manifest that no scenario implements: the suite refuses to run when there are any. */
    fun missing(scenarios: List<Scenario> = allScenarios()): List<String> = ids() - scenarios.map { it.id }.toSet()
}
