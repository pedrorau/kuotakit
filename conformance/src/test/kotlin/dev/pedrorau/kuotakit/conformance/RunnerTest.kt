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

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress

private val contract = Contract.bundled()

/** A backend that answers the health check and nothing else (a plain 404 for the rest). */
private fun <T> onlyHealth(block: (String) -> T): T {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val health = exchange.requestURI.path == "/health"
        val body = if (health) """{"status":"ok"}""".toByteArray() else ByteArray(0)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(if (health) 200 else 404, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
        exchange.close()
    }
    server.start()
    try {
        return block("http://127.0.0.1:${server.address.port}")
    } finally {
        server.stop(0)
    }
}

class RunnerTest :
    StringSpec({
        "a backend that only answers the health check fails the scenarios and the report names them" {
            onlyHealth { url ->
                val report = Runner({ Backend(url, contract) }, null).run(Selection(level2 = false))
                val failed = report.results.filter { it.outcome is Outcome.Failed }.map { it.scenario.id }
                failed.contains("L1-01") shouldBe false
                failed.containsAll(listOf("L1-02", "L1-03", "L1-04", "L1-05", "L1-06")) shouldBe true
                report.verdict.exitCode shouldBe 1
                report.text() shouldContain "Result: NOT conformant: failed L1-02"
            }
        }

        "a scenario that throws, whatever it throws, is reported as failed and the others still run" {
            val scenarios =
                listOf(
                    Scenario("L1-90", Level.ONE) { check(false) { "found a problem" } },
                    Scenario("L1-91", Level.ONE) { error("something else") },
                    Scenario("L1-92", Level.ONE) { },
                )
            val report = Runner({ Backend("http://127.0.0.1:1", contract) }, null, scenarios).run(Selection())
            report.results.map { it.outcome::class } shouldBe
                listOf(Outcome.Failed::class, Outcome.Failed::class, Outcome.Passed::class)
            (report.results[0].outcome as Outcome.Failed).reason shouldBe "found a problem"
        }

        "a scenario for the reference backend is skipped unless asked, and level 2 without a fake Google" {
            val scenarios =
                listOf(
                    Scenario("R-90", Level.ONE, referenceOnly = true) { },
                    Scenario("L2-90", Level.TWO) { },
                )
            val runner = Runner({ Backend("http://127.0.0.1:1", contract) }, null, scenarios)
            runner.run(Selection()).results.map { it.outcome::class } shouldBe
                listOf(Outcome.Skipped::class, Outcome.Skipped::class)
            runner
                .run(Selection(reference = true))
                .results
                .first()
                .outcome shouldBe Outcome.Passed
        }

        "an answer that breaks the contract fails the scenario even if the scenario looked at nothing in it" {
            onlyHealth { url ->
                val scenarios = listOf(Scenario("L1-93", Level.ONE) { backend.call("GET", "/v1/offerings") })
                val report = Runner({ Backend(url, contract) }, null, scenarios).run(Selection())
                report.results.single().outcome shouldBe Outcome.Passed // a plain 404 is not described: not a violation
            }
        }

        "every call of one scenario comes from an address of its own, and another run from other ones" {
            val a = Backend("http://x", contract)
            val b = Backend("http://x", contract)
            val addresses = List(300) { a.freshAddress() } + List(300) { b.freshAddress() }
            addresses.toSet().size shouldBe addresses.size
        }
    })
