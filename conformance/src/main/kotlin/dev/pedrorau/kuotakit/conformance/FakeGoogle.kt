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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val PLAY = "/androidpublisher/v3/applications/"

/**
 * A Google for the backend under test to talk to (ADR 0018): the Play Developer API, the OAuth token endpoint and the
 * signing keys of the Pub/Sub tokens, plus a control API that scripts the answers of a scenario and keeps the log of
 * what the backend asked. Meant for a closed test network: the control API has no authentication, by design.
 *
 * - `POST /control/answer` `{"method","path","status","body"}`: from now on that request gets this answer.
 * - `GET /control/calls`: every call the backend made, as `{"method","path"}`, oldest first.
 * - `POST /control/reset`: forgets the answers and the log.
 * A Play call nobody scripted gets a 404 (a purchase Google does not know), except an acknowledge, which succeeds.
 */
internal class FakeGoogle(
    port: Int,
    val signingKey: SigningKey,
) : AutoCloseable {
    private class Scripted(
        val status: Int,
        val body: String,
    )

    private val answers = ConcurrentHashMap<String, Scripted>()
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    private val issued = ConcurrentHashMap.newKeySet<String>()
    private val counter = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(port), 0)

    val port: Int get() = server.address.port

    init {
        server.executor = executor
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        val body = exchange.requestBody.readBytes().decodeToString()
        when {
            path == "/health" -> {
                send(exchange, Status.OK, """{"status":"ok"}""")
            }

            path.startsWith("/control/") -> {
                control(exchange, path, body)
            }

            else -> {
                calls += method to path
                val scripted = answers["$method $path"]
                if (scripted != null) {
                    send(exchange, scripted.status, scripted.body)
                } else {
                    default(exchange, method, path)
                }
            }
        }
    }

    private fun default(
        exchange: HttpExchange,
        method: String,
        path: String,
    ) {
        when {
            method == "POST" && path == "/token" -> {
                val token = "fake-access-token-${counter.incrementAndGet()}"
                issued += token
                send(exchange, Status.OK, """{"access_token":"$token","expires_in":3600,"token_type":"Bearer"}""")
            }

            method == "GET" && path == "/oauth2/v1/certs" -> {
                send(exchange, Status.OK, signingKey.pemMapJson())
            }

            method == "GET" && path == "/oauth2/v3/certs" -> {
                send(exchange, Status.OK, signingKey.jwksJson())
            }

            path.startsWith(PLAY) -> {
                play(exchange, method, path)
            }

            else -> {
                send(
                    exchange,
                    Status.NOT_FOUND,
                    error(Status.NOT_FOUND, "NOT_FOUND", "No such path in the fake Google."),
                )
            }
        }
    }

    private fun play(
        exchange: HttpExchange,
        method: String,
        path: String,
    ) {
        val bearer = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        when {
            bearer == null || bearer !in issued -> {
                send(
                    exchange,
                    Status.UNAUTHORIZED,
                    error(Status.UNAUTHORIZED, "UNAUTHENTICATED", "The access token was not issued by this fake."),
                )
            }

            method == "POST" && path.endsWith(":acknowledge") -> {
                send(exchange, Status.NO_CONTENT, "")
            }

            else -> {
                send(
                    exchange,
                    Status.NOT_FOUND,
                    error(Status.NOT_FOUND, "NOT_FOUND", "The purchase token was not found."),
                )
            }
        }
    }

    private fun control(
        exchange: HttpExchange,
        path: String,
        body: String,
    ) {
        when (path) {
            "/control/answer" -> {
                val answer = Json.parseToJsonElement(body).jsonObject
                val key =
                    answer.getValue("method").jsonPrimitive.content + " " +
                        answer.getValue("path").jsonPrimitive.content
                answers[key] =
                    Scripted(
                        answer.getValue("status").jsonPrimitive.int,
                        answer["body"]?.jsonPrimitive?.content.orEmpty(),
                    )
                send(exchange, Status.NO_CONTENT, "")
            }

            "/control/calls" -> {
                send(
                    exchange,
                    Status.OK,
                    buildJsonArray {
                        calls.forEach { (method, callPath) ->
                            add(
                                buildJsonObject {
                                    put("method", method)
                                    put("path", callPath)
                                },
                            )
                        }
                    }.toString(),
                )
            }

            "/control/reset" -> {
                answers.clear()
                calls.clear()
                send(exchange, Status.NO_CONTENT, "")
            }

            else -> {
                send(exchange, Status.NOT_FOUND, error(Status.NOT_FOUND, "NOT_FOUND", "No such control call."))
            }
        }
    }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(
            status,
            if (bytes.isEmpty() ||
                status == Status.NO_CONTENT
            ) {
                -1
            } else {
                bytes.size.toLong()
            },
        )
        if (bytes.isNotEmpty() && status != Status.NO_CONTENT) exchange.responseBody.write(bytes)
    }

    private fun error(
        code: Int,
        status: String,
        message: String,
    ): String =
        buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    put("status", status)
                },
            )
        }.toString()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

/** The control API of a fake Google, seen from the suite. */
internal class FakeGoogleControl(
    private val baseUrl: String,
    private val http: Http = Http(),
) {
    fun reset() {
        val reply = http.send("POST", "$baseUrl/control/reset")
        check(reply.status == Status.NO_CONTENT) { "The fake Google did not reset: $reply" }
    }

    /** From now on `method path` (the path as the backend sends it, with no query) gets [status] and [body]. */
    fun answer(
        method: String,
        path: String,
        status: Int,
        body: String = "",
    ) {
        val json: JsonObject =
            buildJsonObject {
                put("method", method)
                put("path", path)
                put("status", status)
                put("body", body)
            }
        val reply =
            http.send(
                "POST",
                "$baseUrl/control/answer",
                mapOf("Content-Type" to "application/json"),
                json.toString(),
            )
        check(reply.status == Status.NO_CONTENT) { "The fake Google did not take the answer: $reply" }
    }

    /** What the backend asked the fake, oldest first, as `GET /path`. */
    fun calls(): List<String> {
        val reply = http.send("GET", "$baseUrl/control/calls")
        check(reply.status == Status.OK) { "The fake Google did not give its log: $reply" }
        return Json.parseToJsonElement(reply.body).let { it as kotlinx.serialization.json.JsonArray }.map {
            it.jsonObject
                .getValue("method")
                .jsonPrimitive.content + " " +
                it.jsonObject
                    .getValue("path")
                    .jsonPrimitive.content
        }
    }
}
