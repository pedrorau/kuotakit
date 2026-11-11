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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** The fake Google of a Level 2 scenario (the runner skips Level 2 when there is none). */
internal val Context.fake: FakeGoogleControl get() =
    google
        ?: throw ScenarioFailure("this scenario needs the fake Google")

/** Tells the fake what Google answers when the backend reads this subscription or one-time purchase. */
internal fun Context.scriptSubscription(
    token: String,
    json: String,
    status: Int = Status.OK,
) = fake.answer("GET", GoogleJson.subscriptionPath(token), status, json)

internal fun Context.scriptOneTime(
    token: String,
    json: String,
    status: Int = Status.OK,
) = fake.answer("GET", GoogleJson.oneTimePath(token), status, json)

/** The app sends the receipt of a purchase. */
internal fun Context.submit(
    user: String,
    productId: String,
    token: String,
): Reply = backend.post("/v1/subscribers/$user/receipts", receipt(token, productId))

/** The customer info of [user], which must be answered. */
internal fun Context.info(user: String): JsonObject {
    val reply = backend.get("/v1/subscribers/$user")
    reply.expect(Status.OK, null, "GET /v1/subscribers/$user")
    return json(reply)
}

/** The user has nothing: no entitlement, no active subscription, no one-time purchase. */
internal fun Context.expectNothing(
    user: String,
    what: String,
) {
    val info = info(user)
    expectEqual(0, info.getValue("entitlements").jsonObject.size, "$what: the entitlements")
    expectEqual(JsonArray(emptyList()), info.getValue("active_subscriptions"), "$what: the active subscriptions")
    expectEqual(JsonArray(emptyList()), info.getValue("non_subscription_purchases"), "$what: the one-time purchases")
}

/** What the backend has asked the fake so far, `GET /path` as the log writes it. */
internal fun Context.acknowledgements(): List<String> = fake.calls().filter { it.endsWith(":acknowledge") }

internal fun Context.reads(): List<String> = fake.calls().filter { it.startsWith("GET /androidpublisher") }
