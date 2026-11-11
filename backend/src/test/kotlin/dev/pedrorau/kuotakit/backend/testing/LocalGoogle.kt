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

package dev.pedrorau.kuotakit.backend.testing

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Google that lives at a local address: a plain HTTP server that answers what a test scripts and notes everything it
 * was asked. It stands for the Play Developer API, the OAuth token endpoint and the signing keys of the push tokens.
 */
class LocalGoogle : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val answers = ConcurrentHashMap<String, String>()

    /** Every request, as `METHOD /path`, in the order it arrived. */
    val requests = CopyOnWriteArrayList<String>()

    /** The `Authorization` header of every request, in the same order (`null` when there was none). */
    val authorizations = CopyOnWriteArrayList<String?>()

    /** The body of every request, in the same order. */
    val bodies = CopyOnWriteArrayList<String>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/"

    init {
        server.createContext("/") { exchange ->
            val key = "${exchange.requestMethod} ${exchange.requestURI.rawPath}"
            requests += key
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            bodies += exchange.requestBody.readBytes().decodeToString()
            val answer = answers[key]
            val bytes = (answer ?: """{"error":{"code":404,"message":"not scripted: $key"}}""").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (answer == null) 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    /** Scripts what the server answers (with a 200) to `method path`. */
    fun answer(
        method: String,
        path: String,
        body: String,
    ) {
        answers["$method $path"] = body
    }

    /** A service account key file whose `token_uri` is this server, with a throw-away private key. */
    fun serviceAccountKeyFile(): Path {
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(key.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        val json =
            buildJsonObject {
                put("type", "service_account")
                put("project_id", "test-project")
                put("private_key_id", "key1")
                put("private_key", pem)
                put("client_email", "backend@test-project.iam.gserviceaccount.com")
                put("client_id", "1")
                put("token_uri", baseUrl + "token")
            }
        return Files.createTempFile("service-account", ".json").also {
            Files.writeString(it, json.toString())
            it.toFile().deleteOnExit()
        }
    }

    override fun close() = server.stop(0)
}
