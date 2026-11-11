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

import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

private fun Context.healthNeedsNoKey() {
    val reply = backend.get("/health", key = null)
    reply.expect(Status.OK, null, "GET /health with no key")
    expectEqual("ok", json(reply).getValue("status").jsonPrimitive.content, "the status of /health")
}

private fun Context.badKeysAreRefused() {
    val endpoints =
        listOf(
            Triple("GET", "/v1/offerings", null),
            Triple("GET", "/v1/subscribers/anon_1", null),
            Triple("POST", "/v1/subscribers/anon_1/receipts", receipt()),
        )
    val credentials =
        mapOf(
            "no key" to emptyMap(),
            "a wrong key" to mapOf("Authorization" to "Bearer $UNKNOWN_KEY"),
            "a wrong key that looks like a secret key" to mapOf("Authorization" to "Bearer $UNKNOWN_SECRET"),
            "another scheme" to mapOf("Authorization" to "Basic Zm9vOmJhcg=="),
            "a bearer with no token" to mapOf("Authorization" to "Bearer"),
            "two tokens" to mapOf("Authorization" to "Bearer one two"),
        )
    endpoints.forEach { (method, path, body) ->
        credentials.forEach { (name, headers) ->
            backend
                .call(method, path, key = null, body = body, headers = headers)
                .expect(Status.UNAUTHORIZED, "unauthorized", "$method $path with $name")
        }
    }
    // A wrong key is refused before the body is read: a malformed body does not turn it into a 400.
    backend
        .post("/v1/subscribers/anon_1/receipts", "not json", key = null)
        .expect(Status.UNAUTHORIZED, "unauthorized", "a malformed body with no key")
}

private fun Context.bothKindsOfKeyRead() {
    listOf(backend.appKey to "an app key", backend.secretKey to "a secret key").forEach { (key, name) ->
        backend.get("/v1/offerings", key).expect(Status.OK, null, "GET /v1/offerings with $name")
        backend.get("/v1/subscribers/anon_1", key).expect(Status.OK, null, "GET /v1/subscribers/anon_1 with $name")
    }
}

private fun Context.overTheLimitIsA429() {
    val address = backend.freshAddress()
    val limited = firstLimited(address) { backend.get("/v1/offerings", from = address) }
    limited.expect(Status.TOO_MANY_REQUESTS, "rate_limited", "the call over the limit")
    val wait = limited.header("Retry-After")?.trim()?.toIntOrNull()
    check(wait != null && wait >= 1) {
        "Retry-After must be a whole number of seconds, at least 1, but is '${limited.header("Retry-After")}'"
    }
    backend
        .get("/v1/offerings", from = address)
        .expect(Status.TOO_MANY_REQUESTS, "rate_limited", "the next call, still over the limit")
}

private fun Context.wrongKeysDoNotSpendGoodKeys() {
    val address = backend.freshAddress()
    firstLimited(address) {
        backend.get("/v1/offerings", key = "pk_wrong_key_from_a_flood_${Random.nextInt()}", from = address)
    }.expect(Status.TOO_MANY_REQUESTS, "rate_limited", "the flood of wrong keys, at its limit")
    backend.get("/v1/offerings", from = address).expect(Status.OK, null, "a good key from the address of the flood")
}

/** Level 1, who may call and how often: the keys and the request limits. */
internal fun level1AccessScenarios(): List<Scenario> =
    listOf(
        Scenario("L1-01", Level.ONE) { healthNeedsNoKey() },
        Scenario("L1-02", Level.ONE) { badKeysAreRefused() },
        Scenario("L1-03", Level.ONE) { bothKindsOfKeyRead() },
        Scenario("L1-09", Level.ONE) { overTheLimitIsA429() },
        Scenario("L1-10", Level.ONE) { wrongKeysDoNotSpendGoodKeys() },
    )
