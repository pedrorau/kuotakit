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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val PLAY = "/androidpublisher/v3/applications/"

/** What the suite scripted and what the backend asked, shared by the two servers of the fake. */
internal class FakeState {
    class Scripted(
        val status: Int,
        val body: String,
    )

    val answers = ConcurrentHashMap<String, Scripted>()
    val calls = CopyOnWriteArrayList<Pair<String, String>>()
    val issued: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val counter = AtomicInteger()

    fun reset() {
        answers.clear()
        calls.clear()
    }
}

/**
 * A Google for the backend under test to talk to (ADR 0018): the Play Developer API, the OAuth token endpoint and the
 * signing keys of the Pub/Sub tokens, on one port; and on another port a control API that scripts the answers of a
 * scenario, keeps the log of what the backend asked, makes push tokens and can switch the Google port off, so that a
 * connection is really refused. Meant for a closed test network: the control API has no authentication, by design
 * (see [ControlApi]). A Play call nobody scripted gets a 404 (a purchase Google does not know), except an
 * acknowledge, which succeeds.
 */
internal class FakeGoogle(
    port: Int,
    controlPort: Int,
    val signingKey: SigningKey,
) : AutoCloseable {
    private val state = FakeState()
    private val executor = Executors.newCachedThreadPool()
    private var google: HttpServer? = null
    private val control = HttpServer.create(InetSocketAddress(controlPort), 0)

    /** The port of the Google API; it stays the same when the API is switched off and on again. */
    val port: Int

    val controlPort: Int get() = control.address.port

    init {
        google = startGoogle(port)
        this.port = google?.address?.port ?: port
        val api = ControlApi(state, signingKey, ::switchGoogle)
        control.executor = executor
        control.createContext("/") { exchange -> exchange.use { api.handle(it) } }
        control.start()
    }

    private fun startGoogle(port: Int): HttpServer =
        HttpServer.create(InetSocketAddress(port), 0).also {
            it.executor = executor
            it.createContext("/") { exchange -> exchange.use { handle(it) } }
            it.start()
        }

    /** Off: the port stops listening, so a connection is refused. On: it listens again on the same port. */
    @Synchronized
    private fun switchGoogle(on: Boolean) {
        if (on && google == null) google = startGoogle(port)
        if (!on) {
            google?.stop(0)
            google = null
        }
    }

    private fun handle(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.rawPath
        exchange.requestBody.readBytes()
        if (path == "/health") return respond(exchange, Status.OK, """{"status":"ok"}""")
        state.calls += method to path
        val scripted = state.answers["$method $path"]
        if (scripted != null) respond(exchange, scripted.status, scripted.body) else default(exchange, method, path)
    }

    private fun default(
        exchange: HttpExchange,
        method: String,
        path: String,
    ) {
        when {
            method == "POST" && path == "/token" -> {
                val token = "fake-access-token-${state.counter.incrementAndGet()}"
                state.issued += token
                respond(exchange, Status.OK, """{"access_token":"$token","expires_in":3600,"token_type":"Bearer"}""")
            }

            method == "GET" && path == "/oauth2/v1/certs" -> {
                respond(exchange, Status.OK, signingKey.pemMapJson())
            }

            method == "GET" && path == "/oauth2/v3/certs" -> {
                respond(exchange, Status.OK, signingKey.jwksJson())
            }

            path.startsWith(PLAY) -> {
                play(exchange, method, path)
            }

            else -> {
                respond(exchange, Status.NOT_FOUND, googleError(Status.NOT_FOUND, "NOT_FOUND", "No such path."))
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
            bearer == null || bearer !in state.issued -> {
                respond(
                    exchange,
                    Status.UNAUTHORIZED,
                    googleError(
                        Status.UNAUTHORIZED,
                        "UNAUTHENTICATED",
                        "The access token was not issued by this fake.",
                    ),
                )
            }

            method == "POST" && path.endsWith(":acknowledge") -> {
                respond(exchange, Status.NO_CONTENT, "")
            }

            else -> {
                respond(
                    exchange,
                    Status.NOT_FOUND,
                    googleError(Status.NOT_FOUND, "NOT_FOUND", "The purchase token was not found."),
                )
            }
        }
    }

    override fun close() {
        google?.stop(0)
        control.stop(0)
        executor.shutdownNow()
    }
}

/** Sends [body] as JSON; a 204 has none. */
internal fun respond(
    exchange: HttpExchange,
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray()
    val empty = bytes.isEmpty() || status == Status.NO_CONTENT
    exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
    exchange.sendResponseHeaders(status, if (empty) -1 else bytes.size.toLong())
    if (!empty) exchange.responseBody.write(bytes)
}

/** The body of an error of the Google APIs. */
internal fun googleError(
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

/**
 * The control API of the fake, on its own port:
 * - `POST /control/answer` `{"method","path","status","body"}`: from now on that request gets this answer;
 * - `GET /control/calls`: every call the backend made, as `{"method","path"}`, oldest first;
 * - `POST /control/reset`: forgets the answers and the log;
 * - `POST /control/push-token` (a spec, see [PushSigner]): the text of a signed push token;
 * - `POST /control/google-down` and `/control/google-up`: the Google port refuses connections, or listens again.
 */
internal class ControlApi(
    private val state: FakeState,
    private val signingKey: SigningKey,
    private val switchGoogle: (Boolean) -> Unit,
) {
    fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().decodeToString()
        when (exchange.requestURI.path) {
            "/health" -> {
                respond(exchange, Status.OK, """{"status":"ok"}""")
            }

            "/control/answer" -> {
                val answer = Json.parseToJsonElement(body).jsonObject
                val key = answer.text("method") + " " + answer.text("path")
                state.answers[key] =
                    FakeState.Scripted(
                        answer.getValue("status").jsonPrimitive.int,
                        answer["body"]?.jsonPrimitive?.content.orEmpty(),
                    )
                respond(exchange, Status.NO_CONTENT, "")
            }

            "/control/calls" -> {
                respond(exchange, Status.OK, callsJson())
            }

            "/control/reset" -> {
                state.reset()
                switchGoogle(true)
                respond(exchange, Status.NO_CONTENT, "")
            }

            "/control/push-token" -> {
                respond(exchange, Status.OK, PushSigner.token(signingKey, Json.parseToJsonElement(body).jsonObject))
            }

            "/control/google-down" -> {
                switchGoogle(false)
                respond(exchange, Status.NO_CONTENT, "")
            }

            "/control/google-up" -> {
                switchGoogle(true)
                respond(exchange, Status.NO_CONTENT, "")
            }

            else -> {
                respond(exchange, Status.NOT_FOUND, googleError(Status.NOT_FOUND, "NOT_FOUND", "No such control call."))
            }
        }
    }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun callsJson() =
        buildJsonArray {
            state.calls.forEach { (method, path) ->
                add(
                    buildJsonObject {
                        put("method", method)
                        put("path", path)
                    },
                )
            }
        }.toString()
}

/** The control API of a fake Google, seen from the suite. */
internal class FakeGoogleControl(
    private val baseUrl: String,
    private val http: Http = Http(),
) {
    fun reset() = expectNoContent("/control/reset", "reset")

    /** From now on `method path` (the path as the backend sends it, with no query) gets [status] and [body]. */
    fun answer(
        method: String,
        path: String,
        status: Int,
        body: String = "",
    ) {
        val json =
            buildJsonObject {
                put("method", method)
                put("path", path)
                put("status", status)
                put("body", body)
            }
        expectNoContent("/control/answer", "take the answer", json.toString())
    }

    /** What the backend asked the fake, oldest first, as `GET /path`. */
    fun calls(): List<String> {
        val reply = http.send("GET", "$baseUrl/control/calls")
        check(reply.status == Status.OK) { "The fake Google did not give its log: $reply" }
        return Json.parseToJsonElement(reply.body).jsonArray.map {
            it.jsonObject
                .getValue("method")
                .jsonPrimitive.content + " " +
                it.jsonObject
                    .getValue("path")
                    .jsonPrimitive.content
        }
    }

    /** A signed push token as [PushSigner] describes: [spec] holds only what differs from a valid one. */
    fun pushToken(spec: JsonObject): String {
        val reply = http.send("POST", "$baseUrl/control/push-token", JSON, spec.toString())
        check(reply.status == Status.OK) { "The fake Google did not make a push token: $reply" }
        return reply.body
    }

    /** The Google port refuses connections until [up]. */
    fun down() = expectNoContent("/control/google-down", "go down")

    fun up() = expectNoContent("/control/google-up", "come up")

    private fun expectNoContent(
        path: String,
        what: String,
        body: String? = null,
    ) {
        val reply = http.send("POST", baseUrl + path, if (body == null) emptyMap() else JSON, body)
        check(reply.status == Status.NO_CONTENT) { "The fake Google did not $what: $reply" }
    }

    private companion object {
        val JSON = mapOf("Content-Type" to "application/json")
    }
}
