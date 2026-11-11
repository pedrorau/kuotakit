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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun shapeOf(offerings: JsonObject) =
    offerings.getValue("offerings").jsonArray.associate { offering ->
        val o = offering.jsonObject
        o.getValue("id").jsonPrimitive.content to
            o.getValue("packages").jsonArray.associate {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content to it.jsonObject.getValue("products")
            }
    }

private fun Context.offeringsEqualTheFixture() {
    val reply = backend.get("/v1/offerings")
    reply.expect(Status.OK, null, "GET /v1/offerings")
    val expected = Json.parseToJsonElement(Fixture.OFFERINGS_JSON).jsonObject
    expectEqual(expected.getValue("current"), json(reply).getValue("current"), "the current offering")
    expectEqual(shapeOf(expected), shapeOf(json(reply)), "the offerings of the fixture entitlements.yaml")
}

private fun Context.unknownUserIsEmpty() {
    val user = freshUser()
    val reply = backend.get("/v1/subscribers/$user")
    reply.expect(Status.OK, null, "GET /v1/subscribers/$user")
    val info = json(reply)
    expectEqual(user, info.getValue("app_user_id").jsonPrimitive.content, "the app user id")
    expectEqual(0, info.getValue("entitlements").jsonObject.size, "the entitlements of an unknown user")
    expectEqual(JsonArray(emptyList()), info.getValue("active_subscriptions"), "the active subscriptions")
    expectEqual(JsonArray(emptyList()), info.getValue("non_subscription_purchases"), "the one-time purchases")
}

private fun Context.invalidIdsAreRefused() {
    val invalid = mapOf("dots" to "..", "129 characters" to "a".repeat(LONG_ID), "a space" to "a%20b")
    invalid.forEach { (name, id) ->
        backend.get("/v1/subscribers/$id").expect(Status.BAD_REQUEST, "invalid_payload", "GET with the id of $name")
    }
    // One receipt only: every invalid id shares one budget of receipts (ADR 0016), whatever the client address, so
    // more of them would make a repeated run answer 429 instead of 400.
    backend
        .post("/v1/subscribers/a%20b/receipts", receipt())
        .expect(Status.BAD_REQUEST, "invalid_payload", "POST a receipt with an invalid id")
}

private fun Context.badBodiesAreRefused() {
    val path = "/v1/subscribers/${freshUser()}/receipts"
    val bodies =
        mapOf(
            "a body over 16 KiB" to receipt("t".repeat(BODY_LIMIT + 1)),
            "text that is not JSON" to "not json",
            "an empty body" to "",
            "an empty object" to "{}",
            "an unknown store" to """{"store":"nope"}""",
            "a receipt with a missing field" to """{"store":"play_store","package_name":"${Fixture.PACKAGE_NAME}"}""",
        )
    bodies.forEach { (name, body) ->
        backend.post(path, body).expect(Status.BAD_REQUEST, "invalid_payload", "POST $name")
    }
}

private fun Context.unknownPathsArePlain() {
    val unknown =
        listOf(
            "GET" to "/v1/nothing",
            "GET" to "/nothing",
            "DELETE" to "/v1/offerings",
            "PUT" to "/v1/subscribers/anon_1",
            "GET" to "/v1/subscribers/anon_1/receipts",
        )
    unknown.forEach { (method, path) ->
        val reply = backend.call(method, path)
        check(reply.status in setOf(Status.NOT_FOUND, Status.METHOD_NOT_ALLOWED)) {
            "$method $path: expected 404 or 405 but was $reply"
        }
        check(reply.body.isBlank()) { "$method $path: a plain ${reply.status} has no body, but this one has: $reply" }
    }
}

private fun Context.everyAnswerIsDescribed() {
    val user = freshUser()
    val address = backend.freshAddress()
    val receipts = "/v1/subscribers/$user/receipts"
    val answers =
        listOf(
            Triple("GET", "/health", backend.get("/health", key = null)),
            Triple("GET", "/v1/offerings", backend.get("/v1/offerings")),
            Triple("GET", "/v1/offerings", backend.get("/v1/offerings", key = null)),
            Triple("GET", "/v1/subscribers/$user", backend.get("/v1/subscribers/$user")),
            Triple("GET", "/v1/subscribers/a%20b", backend.get("/v1/subscribers/a%20b")),
            Triple("GET", "/v1/subscribers/$user", backend.get("/v1/subscribers/$user", key = null)),
            Triple("POST", receipts, backend.post(receipts, "not json")),
            Triple("POST", receipts, backend.post(receipts, receipt(), key = null)),
            Triple("GET", "/v1/offerings", firstLimited(address) { backend.get("/v1/offerings", from = address) }),
        )
    answers.forEach { (method, path, reply) ->
        check(backend.describes(method, path, reply)) {
            "$method $path answered ${reply.status}, which docs/api/openapi.json does not describe"
        }
    }
}

private fun Context.errorCodesAreTheTable() {
    val address = backend.freshAddress()
    backend.get("/v1/subscribers/a%20b").expect(Status.BAD_REQUEST, "invalid_payload", "an invalid id")
    backend.get("/v1/offerings", key = null).expect(Status.UNAUTHORIZED, "unauthorized", "no key")
    backend
        .post("/v1/subscribers/${freshUser()}/receipts", "not json")
        .expect(Status.BAD_REQUEST, "invalid_payload", "a malformed receipt")
    firstLimited(address) { backend.get("/v1/offerings", from = address) }
        .expect(Status.TOO_MANY_REQUESTS, "rate_limited", "over the limit")
    // Every other error that the backend sends in any scenario is checked against the table on the way (ErrorTable).
}

private fun Context.appStoreIsNotYet() {
    val body = """{"store":"app_store","bundle_id":"${Fixture.PACKAGE_NAME}","signed_transaction":"x"}"""
    backend
        .post("/v1/subscribers/${freshUser()}/receipts", body)
        .expect(Status.BAD_REQUEST, "invalid_payload", "an App Store receipt")
}

/** Level 1, what the answers look like: offerings, customer info, validation, the contract and its errors. */
internal fun level1ShapeScenarios(): List<Scenario> =
    listOf(
        Scenario("L1-04", Level.ONE) { offeringsEqualTheFixture() },
        Scenario("L1-05", Level.ONE) { unknownUserIsEmpty() },
        Scenario("L1-06", Level.ONE) { invalidIdsAreRefused() },
        Scenario("L1-07", Level.ONE) { badBodiesAreRefused() },
        Scenario("L1-08", Level.ONE) { unknownPathsArePlain() },
        Scenario("L1-11", Level.ONE) { everyAnswerIsDescribed() },
        Scenario("L1-12", Level.ONE) { errorCodesAreTheTable() },
        Scenario("R-01", Level.ONE, referenceOnly = true) { appStoreIsNotYet() },
    )
