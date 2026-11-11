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

/**
 * Checks the suite itself: it must pass against the backend as it is, and each [Mutation] of what the backend
 * answers must turn the scenario that is meant to notice it red. A suite that passes a corrupted backend proves
 * nothing.
 */
internal class SelfCheck(
    private val backendUrl: String,
    private val contract: Contract,
    private val google: FakeGoogleControl?,
    private val selection: Selection,
    private val print: (String) -> Unit = ::println,
) {
    fun run(): Boolean {
        val baseline = Runner({ Backend(backendUrl, contract) }, google).run(selection)
        print("Without a mutation:")
        print(baseline.text())
        var ok = baseline.verdict.exitCode == 0
        if (!ok) print("The suite must pass the backend as it is before its own check means anything.")
        Mutation.entries.filter { selection.level2 || it.level == Level.ONE }.forEach { mutation ->
            MutantProxy(backendUrl, mutation).use { proxy ->
                val report = Runner({ Backend(proxy.url, contract) }, google).run(selection)
                val failed =
                    report.results
                        .filter { it.outcome is Outcome.Failed }
                        .map { it.scenario.id }
                        .toSet()
                val missed = mutation.catchers - failed
                print("${mutation.name} (${mutation.description}): caught by ${failed.sorted()}")
                report.results.filter { it.outcome is Outcome.Failed }.forEach {
                    print("    ${it.scenario.id}: ${(it.outcome as Outcome.Failed).reason.take(REASON_SHOWN)}")
                }
                if (missed.isNotEmpty()) {
                    print("  NOT CAUGHT by ${missed.sorted()}: the suite does not notice this corruption")
                    ok = false
                }
            }
        }
        print(if (ok) "Self-check: every mutation was caught." else "Self-check: FAILED.")
        return ok
    }
}

private const val REASON_SHOWN = 160
