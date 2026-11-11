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

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The backend under test, seen as a client sees it. Every call comes from an address of its own, told to the backend
 * in `X-Forwarded-For` (the suite plays the reverse proxy, ADR 0016), so that the request limits of one scenario never
 * leak into another and a run can be repeated; [from] pins the address when a scenario is about the limits.
 * Every answer is checked against the contract on the way; the scenario fails if one breaks it.
 */
internal class Backend(
    val baseUrl: String,
    private val contract: Contract,
    private val http: Http = Http(),
    val appKey: String = Fixture.APP_KEY,
    val secretKey: String = Fixture.SECRET_KEY,
    private val prefix: Int = Random.nextInt(PREFIX_SPACE),
) {
    private val calls = AtomicInteger()
    val violations = mutableListOf<String>()

    /** A client address nobody else used in this run: `10.x.y.z`, its first two octets random, so runs differ. */
    fun freshAddress(): String {
        val n = calls.incrementAndGet() - 1
        val high = (prefix / OCTETS + n / LAST_OCTET) % OCTETS
        return "10.${prefix % OCTETS}.$high.${n % LAST_OCTET + 1}"
    }

    /** Returns the answer. [path] is what the backend sees; a [key] of `null` sends no `Authorization` header. */
    @Suppress("LongParameterList") // the parts of one request
    fun call(
        method: String,
        path: String,
        key: String? = appKey,
        body: String? = null,
        from: String = freshAddress(),
        headers: Map<String, String> = emptyMap(),
    ): Reply {
        val sent =
            buildMap {
                put("X-Forwarded-For", from)
                if (key != null) put("Authorization", "Bearer $key")
                if (body != null) put("Content-Type", "application/json")
                putAll(headers)
            }
        val reply = http.send(method, baseUrl.trimEnd('/') + path, sent, body)
        contract.violations(method, path.substringBefore('?'), reply).forEach { violations += "$method $path: $it" }
        ErrorTable.problem(reply)?.let { violations += "$method $path: $it" }
        return reply
    }

    fun get(
        path: String,
        key: String? = appKey,
        from: String = freshAddress(),
    ) = call("GET", path, key, null, from)

    fun post(
        path: String,
        body: String,
        key: String? = appKey,
        from: String = freshAddress(),
    ) = call("POST", path, key, body, from)

    /** Whether the contract describes this answer (the scenario of the contract insists on it). */
    fun describes(
        method: String,
        path: String,
        reply: Reply,
    ) = contract.describes(method, path, reply)

    private companion object {
        const val OCTETS = 256
        const val LAST_OCTET = 254
        const val PREFIX_SPACE = OCTETS * OCTETS
    }
}
