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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * A way of corrupting what the backend answers, to check the suite itself: a suite that cannot tell a broken backend
 * from a good one proves nothing. [catchers] are the scenarios that must notice the corruption.
 */
internal enum class Mutation(
    val description: String,
    val level: Level,
    val catchers: Set<String>,
    val change: (method: String, path: String, reply: Reply) -> Reply,
) {
    KEY_ACCEPTED(
        "a 401 turned into a 200: a wrong key is accepted",
        Level.ONE,
        setOf("L1-02"),
        { _, _, reply -> if (reply.status == Status.UNAUTHORIZED) reply.copy(status = Status.OK) else reply },
    ),
    ACCESS_FOR_UNKNOWN_USER(
        "an entitlement granted to a user who bought nothing",
        Level.ONE,
        setOf("L1-05"),
        { method, path, reply ->
            if (method == "GET" && path.startsWith("/v1/subscribers/") && reply.status == Status.OK) {
                reply.copy(body = grant(reply.body))
            } else {
                reply
            }
        },
    ),
    FIELD_REMOVED(
        "a required field removed from the customer info",
        Level.ONE,
        setOf("L1-05", "L1-11"),
        { method, path, reply ->
            if (method == "GET" && path.startsWith("/v1/subscribers/") && reply.status == Status.OK) {
                reply.copy(
                    body =
                        Json.encodeToString(
                            JsonObject.serializer(),
                            JsonObject(
                                reply.body.asObject().filterKeys {
                                    it !=
                                        "non_subscription_purchases"
                                },
                            ),
                        ),
                )
            } else {
                reply
            }
        },
    ),
    RETRY_AFTER_DROPPED(
        "the Retry-After header dropped from a 429",
        Level.ONE,
        setOf("L1-09"),
        { _, _, reply ->
            if (reply.status ==
                Status.TOO_MANY_REQUESTS
            ) {
                reply.copy(headers = reply.headers.filterKeys { !it.equals("Retry-After", true) })
            } else {
                reply
            }
        },
    ),
    LIMIT_REMOVED(
        "a 429 turned into a 200: the backend never limits",
        Level.ONE,
        setOf("L1-09", "L1-10"),
        { _, _, reply -> if (reply.status == Status.TOO_MANY_REQUESTS) reply.copy(status = Status.OK) else reply },
    ),
    PURCHASE_REFUSAL_ACCEPTED(
        "a 402 turned into a 200: a purchase Google does not confirm is accepted",
        Level.TWO,
        setOf("L2-07", "L2-15"),
        { _, _, reply -> if (reply.status == Status.PURCHASE_INVALID) reply.copy(status = Status.OK) else reply },
    ),
    CONFLICT_ACCEPTED(
        "a 409 turned into a 200: a token of another user is accepted",
        Level.TWO,
        setOf("L2-16", "L2-18"),
        { _, _, reply -> if (reply.status == Status.CONFLICT) reply.copy(status = Status.OK) else reply },
    ),
    STORE_FAILURE_ACCEPTED(
        "a 502 turned into a 200: a failure of the store is treated as success",
        Level.TWO,
        setOf("L2-10", "L2-21", "L2-22"),
        { _, _, reply -> if (reply.status == Status.BAD_GATEWAY) reply.copy(status = Status.OK) else reply },
    ),
    ACCESS_AFTER_ENDING(
        "every entitlement reported as active, as if no refund or expiry ever took access away",
        Level.TWO,
        setOf("L2-03", "L2-26"),
        { method, path, reply ->
            if (method == "GET" && path.startsWith("/v1/subscribers/") && reply.status == Status.OK) {
                reply.copy(body = activateAll(reply.body))
            } else {
                reply
            }
        },
    ),
    UNTRUSTED_PUSH_ACCEPTED(
        "a 401 of the webhook turned into a 204: a push with no valid proof is accepted",
        Level.TWO,
        setOf("L2-24"),
        { _, path, reply ->
            if (path == "/v1/webhooks/google" && reply.status == Status.UNAUTHORIZED) {
                reply.copy(status = Status.NO_CONTENT, body = "")
            } else {
                reply
            }
        },
    ),
}

private fun activateAll(body: String): String {
    val info = body.asObject()
    val entitlements =
        info.getValue("entitlements").jsonObject.mapValues { (_, value) ->
            JsonObject(value.jsonObject + ("is_active" to JsonPrimitive(true)))
        }
    return Json.encodeToString(JsonObject.serializer(), JsonObject(info + ("entitlements" to JsonObject(entitlements))))
}

private fun String.asObject() = Json.parseToJsonElement(this).jsonObject

private fun grant(body: String): String =
    Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            body.asObject() +
                (
                    "entitlements" to
                        buildJsonObject {
                            put(
                                "premium",
                                buildJsonObject {
                                    put("is_active", true)
                                    put("product_id", "premium_monthly")
                                    put("store", "play_store")
                                    put("period_type", "normal")
                                    put("expires_at", "2099-01-01T00:00:00Z")
                                    put("will_renew", true)
                                    put("billing_issue_detected_at", kotlinx.serialization.json.JsonNull)
                                },
                            )
                        }
                ),
        ),
    )

internal fun Reply.copy(
    status: Int = this.status,
    headers: Map<String, List<String>> = this.headers,
    body: String = this.body,
) = Reply(status, headers, body)

/** Stands between the suite and the real backend and applies one [Mutation] to every answer. */
internal class MutantProxy(
    private val target: String,
    private val mutation: Mutation,
    private val http: Http = Http(),
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange -> exchange.use { forward(it) } }
        server.start()
    }

    private fun forward(exchange: HttpExchange) {
        val path =
            exchange.requestURI.rawPath +
                exchange.requestURI.rawQuery
                    ?.let { "?$it" }
                    .orEmpty()
        val headers =
            exchange.requestHeaders
                .filterKeys { it.lowercase() !in RESTRICTED }
                .mapValues { it.value.first() }
        val request =
            exchange.requestBody
                .readBytes()
                .decodeToString()
                .ifEmpty { null }
        val answer = http.send(exchange.requestMethod, target.trimEnd('/') + path, headers, request)
        val changed = mutation.change(exchange.requestMethod, exchange.requestURI.rawPath, answer)
        changed.headers
            .filterKeys { it.lowercase() !in RESTRICTED && !it.startsWith(":") }
            .forEach { (name, values) -> values.forEach { exchange.responseHeaders.add(name, it) } }
        val bytes = changed.body.toByteArray()
        exchange.sendResponseHeaders(changed.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private companion object {
        val RESTRICTED =
            setOf("connection", "content-length", "expect", "host", "upgrade", "transfer-encoding", "keep-alive")
    }
}
