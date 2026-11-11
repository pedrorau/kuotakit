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

package dev.pedrorau.kuotakit.sdk

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

private const val KEY = "kuotakit.customer_info"

/**
 * The last customer info the backend gave, kept on the device, and when it was received. It is **not an answer of the
 * backend now** and the SDK never turns it into access: it is there so that an app can show what the user had when
 * there is no network, and decide for itself how long it trusts it (docs/adr/0019). The file can be edited by the
 * owner of a rooted device, which is why it proves nothing.
 */
public class CachedCustomerInfo(
    public val customerInfo: CustomerInfo,
    public val receivedAt: Instant,
) {
    override fun equals(other: Any?): Boolean =
        other is CachedCustomerInfo && customerInfo == other.customerInfo && receivedAt == other.receivedAt

    override fun hashCode(): Int = listOf(customerInfo, receivedAt).hashCode()

    override fun toString(): String = "CachedCustomerInfo(receivedAt=$receivedAt)"
}

/** Writes the cache as JSON built by hand: the models of the API live in `contracts`, not here (ADR 0004). */
internal fun saveCached(
    store: KeyValueStore,
    cached: CachedCustomerInfo,
) {
    val json =
        JsonObject(
            mapOf(
                "received_at" to JsonPrimitive(cached.receivedAt.toString()),
                "customer_info" to kuotaKitJson.encodeToJsonElement(CustomerInfo.serializer(), cached.customerInfo),
            ),
        )
    store.put(KEY, json.toString())
}

/**
 * Reads the cache. What cannot be read (garbage, a value from a version that wrote it differently), or belongs to
 * another app user, is ignored: a cache never makes anything fail and never crosses users.
 */
@Suppress("TooGenericExceptionCaught") // anything wrong in what is stored means there is no cache
internal fun loadCached(
    store: KeyValueStore,
    appUserId: String,
): CachedCustomerInfo? =
    try {
        val text = store.get(KEY)
        val json = text?.let { kuotaKitJson.parseToJsonElement(it).jsonObject }
        val info =
            json
                ?.getValue(
                    "customer_info",
                )?.let { kuotaKitJson.decodeFromJsonElement(CustomerInfo.serializer(), it) }
        val at =
            json
                ?.getValue("received_at")
                ?.jsonPrimitive
                ?.content
                ?.let { Instant.parse(it) }
        when {
            info == null || at == null -> {
                null
            }

            info.appUserId == appUserId -> {
                CachedCustomerInfo(info, at)
            }

            // Another app user's: it is dropped (the store has no remove, so it is emptied), never shown.
            else -> {
                store.put(KEY, "")
                null
            }
        }
    } catch (ignored: Exception) {
        null
    }

/**
 * Saving is best effort: a storage that fails must never turn an answer of the backend, even a purchase it accepted,
 * into an error for the app. Not saving only means the next run starts without a cache.
 */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun saveCachedQuietly(
    store: KeyValueStore,
    cached: CachedCustomerInfo,
) {
    try {
        saveCached(store, cached)
    } catch (ignored: Exception) {
        // The cache is a convenience.
    }
}
