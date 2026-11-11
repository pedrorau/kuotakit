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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun scenario(
    id: String,
    level: Level,
) = Scenario(id, level) {}

private val one = scenario("L1-01", Level.ONE)
private val two = scenario("L2-01", Level.TWO)

class ReportTest :
    StringSpec({
        "level 1 passing with no level 2 scenario is contract conformant, and says why level 2 was not run" {
            val report = Report(listOf(Result(one, Outcome.Passed)), level2Asked = true)
            report.verdict.line shouldBe "contract conformant (Level 1)"
            report.verdict.exitCode shouldBe 0
            report.text() shouldContain "Level 2 was not run"
        }

        "level 1 passing and level 2 not asked for is contract conformant" {
            val report =
                Report(
                    listOf(Result(one, Outcome.Passed), Result(two, Outcome.Skipped("not asked"))),
                    level2Asked = false,
                )
            report.verdict.line shouldBe "contract conformant (Level 1)"
            report.text() shouldContain "Level 2 was not asked for"
        }

        "both levels passing is fully conformant" {
            val report = Report(listOf(Result(one, Outcome.Passed), Result(two, Outcome.Passed)), level2Asked = true)
            report.verdict.line shouldBe "fully conformant"
            report.verdict.exitCode shouldBe 0
        }

        "a failure of any level is not conformant, exits 1 and names the scenarios" {
            listOf(one, two).forEach { failing ->
                val others = listOf(one, two).filter { it != failing }.map { Result(it, Outcome.Passed) }
                val report = Report(others + Result(failing, Outcome.Failed("because")), level2Asked = true)
                report.verdict.exitCode shouldBe 1
                report.verdict.line shouldBe "NOT conformant: failed ${failing.id}"
                report.text() shouldContain "FAIL  ${failing.id}: because"
            }
        }

        "a skipped scenario is not a pass: level 2 skipped never makes a backend fully conformant" {
            Report(listOf(Result(one, Outcome.Passed), Result(two, Outcome.Skipped("x"))), level2Asked = true)
                .verdict.line shouldBe "contract conformant (Level 1)"
        }
    })
