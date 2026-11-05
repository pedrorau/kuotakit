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

package dev.pedrorau.kuotakit.backend.google

import dev.pedrorau.kuotakit.backend.domain.InvalidNotificationException
import dev.pedrorau.kuotakit.backend.domain.PlayNotification
import dev.pedrorau.kuotakit.backend.domain.PlayPush
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64

/**
 * Reads a Pub/Sub push whose message is a Google Play real-time developer notification
 * (https://developer.android.com/google/play/billing/rtdn-reference). It reads the JSON as a tree instead of
 * declaring models: these shapes belong to Google, not to this API (rule: models live only in `contracts/`).
 */
internal object PubSubPushParser {
    // Price-change notifications: our reading of the reference is that they do not change who has access.
    private val PRICE_ONLY_TYPES = setOf(8, 19, 22)
    private const val TYPE_REVOKED = 12
    private const val PRODUCT_TYPE_SUBSCRIPTION = 1

    /** Throws [InvalidNotificationException] when this is not a push from Pub/Sub carrying a notification. */
    fun parse(body: String): PlayPush =
        try {
            val message = obj(obj(Json.parseToJsonElement(body), "body")["message"], "message")
            val notification = obj(decode(text(message["data"], "message.data")), "data")
            PlayPush(
                messageId = text(message["messageId"], "message.messageId"),
                packageName = text(notification["packageName"], "packageName"),
                notification = notificationOf(notification),
            )
        } catch (e: SerializationException) {
            throw InvalidNotificationException("The body is not a Pub/Sub push of a Play notification.", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidNotificationException("The body is not a Pub/Sub push of a Play notification.", e)
        }

    private fun notificationOf(notification: JsonObject): PlayNotification {
        val subscription = notification["subscriptionNotification"]
        val voided = notification["voidedPurchaseNotification"]
        return when {
            subscription != null -> subscription(obj(subscription, "subscriptionNotification"))
            voided != null -> voided(obj(voided, "voidedPurchaseNotification"))
            notification["testNotification"] != null -> PlayNotification.Ignored("test notification")
            notification["oneTimeProductNotification"] != null -> PlayNotification.Ignored("one-time products")
            else -> PlayNotification.Ignored("not a subscription or voided purchase notification")
        }
    }

    private fun subscription(n: JsonObject): PlayNotification {
        val token = text(n["purchaseToken"], "purchaseToken")
        return when (val type = number(n["notificationType"], "notificationType")) {
            TYPE_REVOKED -> PlayNotification.SubscriptionRevoked(token)

            in PRICE_ONLY_TYPES -> PlayNotification.Ignored("price change notification ($type)")

            // Any other type, including one that does not exist yet, is worth reading again: it is cheap and safe.
            else -> PlayNotification.SubscriptionChanged(token)
        }
    }

    private fun voided(n: JsonObject): PlayNotification =
        if (number(n["productType"], "productType") == PRODUCT_TYPE_SUBSCRIPTION) {
            PlayNotification.PurchaseVoided(text(n["purchaseToken"], "purchaseToken"))
        } else {
            PlayNotification.Ignored("voided one-time purchase")
        }

    private fun decode(data: String): JsonElement =
        Json.parseToJsonElement(String(Base64.getMimeDecoder().decode(data), Charsets.UTF_8))

    private fun obj(
        element: JsonElement?,
        name: String,
    ): JsonObject = element as? JsonObject ?: throw IllegalArgumentException("$name is not an object")

    private fun text(
        element: JsonElement?,
        name: String,
    ): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$name is missing")

    // Google writes some numbers as strings in its examples (`eventTimeMillis`), so both forms are accepted.
    private fun number(
        element: JsonElement?,
        name: String,
    ): Int = (element as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("$name is missing")
}
