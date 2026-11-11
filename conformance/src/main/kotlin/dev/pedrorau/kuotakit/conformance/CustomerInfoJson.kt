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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** The entitlement [name] of a customer info, or `null` if the user never had it. */
internal fun JsonObject.entitlement(name: String): JsonObject? = getValue("entitlements").jsonObject[name]?.jsonObject

internal fun JsonObject.isActive(name: String): Boolean =
    entitlement(name)?.getValue("is_active")?.jsonPrimitive?.boolean
        ?: throw ScenarioFailure("the customer info has no entitlement '$name': $this")

internal fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.takeIf { it.isString }?.content

/** Two moments written differently (Google's and the backend's) are the same moment. */
internal fun expectSameMoment(
    expected: String,
    actual: String?,
    what: String,
) = expectEqual(Instant.parse(expected), actual?.let { Instant.parse(it) }, what)

internal fun JsonObject.activeSubscriptions(): List<String> =
    getValue("active_subscriptions").jsonArray.map { it.jsonPrimitive.content }

internal fun JsonObject.oneTimeProducts(): List<String> =
    getValue("non_subscription_purchases").jsonArray.map {
        it.jsonObject
            .getValue("product_id")
            .jsonPrimitive.content
    }
