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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

internal const val BODY_LIMIT = 16 * 1024
private const val MAX_CALLS_TO_REACH_A_LIMIT = 1000
internal const val LONG_ID = 129
internal const val UNKNOWN_KEY = "pk_this_key_does_not_exist_anywhere"
internal const val UNKNOWN_SECRET = "sk_this_key_does_not_exist_anywhere"

internal fun freshUser() = "anon_conformance_" + Random.nextLong().toString(Character.MAX_RADIX).removePrefix("-")

internal fun receipt(token: String = "token-1") =
    """{"store":"play_store","package_name":"${Fixture.PACKAGE_NAME}","product_id":"premium_monthly","purchase_token":"$token"}"""

internal fun json(reply: Reply): JsonObject = Json.parseToJsonElement(reply.body).jsonObject

internal fun errorCode(reply: Reply): String? =
    runCatching {
        json(reply)
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content
    }.getOrNull()

internal fun Reply.expect(
    status: Int,
    code: String?,
    what: String,
) {
    expectEqual(status, this.status, "$what: the status")
    if (code != null) expectEqual(code, errorCode(this), "$what: the error code")
}

/** Calls until the backend says no (a 429), which it must do within [MAX_CALLS_TO_REACH_A_LIMIT] calls. */
internal fun firstLimited(
    address: String,
    call: () -> Reply,
): Reply {
    repeat(MAX_CALLS_TO_REACH_A_LIMIT) {
        val reply = call()
        if (reply.status == Status.TOO_MANY_REQUESTS) return reply
    }
    throw ScenarioFailure(
        "no 429 after $MAX_CALLS_TO_REACH_A_LIMIT calls from $address: " +
            "the backend needs a low request limit (see docs/conformance.md)",
    )
}
