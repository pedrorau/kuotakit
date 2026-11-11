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
import java.io.File

private val ID = Regex("""\b(?:L[12]-\d{2}|R-\d{2})\b""")

private fun manifestIds() =
    File("manifest.txt")
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.substringBefore(' ') }

/** The manifest is the list of what the suite promises; the scenarios and the document must say the same. */
class ManifestTest :
    StringSpec({
        "every ID of the manifest has a scenario and every scenario has an ID of the manifest" {
            val scenarios = allScenarios().map { it.id }
            scenarios.sorted() shouldBe manifestIds().sorted()
            scenarios.toSet().size shouldBe scenarios.size
        }

        "the manifest has one line per ID, in the form 'ID description'" {
            File("manifest.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                ID.matches(line.substringBefore(' ')) shouldBe true
                line.substringAfter(' ').isNotBlank() shouldBe true
            }
        }

        "the level of a scenario is the one of its ID, and only R scenarios are for the reference backend alone" {
            allScenarios().forEach {
                when (it.id.take(2)) {
                    "L1" -> it.level shouldBe Level.ONE
                    "L2" -> it.level shouldBe Level.TWO
                }
                it.referenceOnly shouldBe it.id.startsWith("R-")
            }
        }

        "docs/conformance.md lists the same IDs as the manifest" {
            val section =
                File("../docs/conformance.md").readText().substringAfter("\n## Manifest\n").substringBefore("\n## ")
            val documented = ID.findAll(section).map { it.value }.toSet()
            documented shouldBe manifestIds().toSet()
        }

        "the error table of the suite is the table Errors of docs/api-spec.md" {
            val table =
                File("../docs/api-spec.md").readText().substringAfter("\n## Errors\n").substringBefore("\n## ")
            val documented =
                Regex("""^\| (\d{3}) \| `([a-z_]+)` \|""", RegexOption.MULTILINE)
                    .findAll(table)
                    .associate { it.groupValues[1].toInt() to it.groupValues[2] }
            documented shouldBe ErrorTable.CODES
        }
    })
