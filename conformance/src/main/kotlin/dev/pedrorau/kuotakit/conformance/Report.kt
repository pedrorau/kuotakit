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

/** How a scenario ended. */
internal sealed interface Outcome {
    data object Passed : Outcome

    data class Failed(
        val reason: String,
    ) : Outcome

    data class Skipped(
        val reason: String,
    ) : Outcome
}

internal data class Result(
    val scenario: Scenario,
    val outcome: Outcome,
)

/**
 * The verdict of a run, in the words of ADR 0007: "fully conformant" is Level 1 and Level 2 passing;
 * "contract conformant (Level 1)" is Level 1 passing with no Level 2 to run or asked for.
 */
internal sealed interface Verdict {
    val exitCode: Int
    val line: String

    data object Full : Verdict {
        override val exitCode = 0
        override val line = "fully conformant"
    }

    data class ContractOnly(
        val why: String,
    ) : Verdict {
        override val exitCode = 0
        override val line = "contract conformant (Level 1)"
    }

    data class Failing(
        val failed: List<String>,
    ) : Verdict {
        override val exitCode = 1
        override val line = "NOT conformant: failed " + failed.joinToString(", ")
    }
}

internal class Report(
    val results: List<Result>,
    private val level2Asked: Boolean,
) {
    private val failed = results.filter { it.outcome is Outcome.Failed }

    val verdict: Verdict
        get() {
            val level2 = results.filter { it.scenario.level == Level.TWO && it.outcome !is Outcome.Skipped }
            return when {
                failed.isNotEmpty() -> Verdict.Failing(failed.map { it.scenario.id })
                level2.isNotEmpty() -> Verdict.Full
                level2Asked -> Verdict.ContractOnly("this version of the suite has no Level 2 scenarios")
                else -> Verdict.ContractOnly("Level 2 was not asked for")
            }
        }

    fun text(): String =
        buildString {
            results.forEach { (scenario, outcome) ->
                when (outcome) {
                    Outcome.Passed -> appendLine("  PASS  ${scenario.id}")
                    is Outcome.Failed -> appendLine("  FAIL  ${scenario.id}: ${outcome.reason}")
                    is Outcome.Skipped -> appendLine("  SKIP  ${scenario.id}: ${outcome.reason}")
                }
            }
            val ran = results.count { it.outcome !is Outcome.Skipped }
            appendLine("$ran scenarios run, ${failed.size} failed, ${results.size - ran} skipped.")
            val verdict = verdict
            if (verdict is Verdict.ContractOnly) appendLine("Level 2 was not run: ${verdict.why}.")
            appendLine("Result: ${verdict.line}")
        }
}
