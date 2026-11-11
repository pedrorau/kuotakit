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
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress

private const val INFO =
    """{"app_user_id":"a","request_date":"2026-01-01T00:00:00Z","entitlements":{},"active_subscriptions":[],""" +
        """"non_subscription_purchases":[]}"""

/** A fixed backend: 401 for `/denied`, 429 with Retry-After for `/limited`, a customer info for the rest. */
private fun <T> backend(block: (String) -> T): T {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val (status, body) =
            when (exchange.requestURI.path) {
                "/denied" -> {
                    401 to """{"error":{"code":"unauthorized","message":"x"}}"""
                }

                "/limited" -> {
                    429 to
                        """{"error":{"code":"rate_limited","message":"x"}}""".also {
                            exchange.responseHeaders.add(
                                "Retry-After",
                                "5",
                            )
                        }
                }

                else -> {
                    200 to INFO
                }
            }
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.length.toLong())
        exchange.responseBody.write(body.toByteArray())
        exchange.close()
    }
    server.start()
    try {
        return block("http://127.0.0.1:${server.address.port}")
    } finally {
        server.stop(0)
    }
}

private fun through(
    mutation: Mutation,
    path: String,
): Reply = backend { url -> MutantProxy(url, mutation).use { Http().send("GET", it.url + path) } }

class MutantProxyTest :
    StringSpec({
        "the proxy passes everything through when the mutation does not apply" {
            val reply = through(Mutation.KEY_ACCEPTED, "/subscribers")
            reply.status shouldBe 200
            reply.body shouldBe INFO
            through(Mutation.KEY_ACCEPTED, "/limited").header("Retry-After") shouldBe "5"
        }

        "each mutation changes what it says it changes" {
            through(Mutation.KEY_ACCEPTED, "/denied").status shouldBe 200
            through(Mutation.LIMIT_REMOVED, "/limited").status shouldBe 200
            through(Mutation.RETRY_AFTER_DROPPED, "/limited").header("Retry-After") shouldBe null
            through(Mutation.RETRY_AFTER_DROPPED, "/limited").status shouldBe 429
            through(Mutation.ACCESS_FOR_UNKNOWN_USER, "/v1/subscribers/a").body shouldContain "premium"
            through(Mutation.FIELD_REMOVED, "/v1/subscribers/a").body shouldNotContain "non_subscription_purchases"
            through(Mutation.FIELD_REMOVED, "/v1/subscribers/a").body shouldContain "entitlements"
        }

        "every mutation names scenarios that exist" {
            val ids = allScenarios().map { it.id }.toSet()
            Mutation.entries.forEach { (it.catchers - ids) shouldBe emptySet() }
            Mutation.entries.forEach { it.catchers.isNotEmpty() shouldBe true }
        }
    })
