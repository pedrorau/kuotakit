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

/** What to run: the levels, and whether this is the reference backend (which also keeps temporary behaviour). */
internal data class Selection(
    val level2: Boolean = true,
    val reference: Boolean = false,
)

/** Runs the scenarios against one backend and reports. Each fails on its own; one failure never stops the rest. */
internal class Runner(
    private val backend: () -> Backend,
    private val google: FakeGoogleControl?,
    private val scenarios: List<Scenario> = allScenarios(),
) {
    fun run(selection: Selection): Report {
        google?.reset()
        val results =
            scenarios.map { scenario ->
                Result(scenario, outcomeOf(scenario, selection))
            }
        return Report(results, selection.level2)
    }

    private fun outcomeOf(
        scenario: Scenario,
        selection: Selection,
    ): Outcome =
        when {
            scenario.referenceOnly && !selection.reference -> {
                Outcome.Skipped("only asked of the reference backend (set CONFORMANCE_REFERENCE=true)")
            }

            scenario.level == Level.TWO && !selection.level2 -> {
                Outcome.Skipped("Level 2 was not asked for")
            }

            scenario.level == Level.TWO && google == null -> {
                Outcome.Skipped("no fake Google to script")
            }

            else -> {
                execute(scenario)
            }
        }

    @Suppress("TooGenericExceptionCaught") // a scenario fails for any reason, and the reason goes in the report
    private fun execute(scenario: Scenario): Outcome {
        val client = backend()
        google?.reset()
        return try {
            scenario.run(Context(client, google))
            if (client.violations.isEmpty()) {
                Outcome.Passed
            } else {
                Outcome.Failed(
                    "an answer does not match docs/api/openapi.json: " + client.violations.joinToString("; "),
                )
            }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }
}
