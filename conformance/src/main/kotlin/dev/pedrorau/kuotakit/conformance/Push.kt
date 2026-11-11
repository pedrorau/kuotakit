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
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.random.Random

/** The notification types of Google Play (https://developer.android.com/google/play/billing/rtdn-reference). */
internal object NotificationType {
    const val RENEWED = 2
    const val REVOKED = 12
    const val ONE_TIME_CANCELED = 2
    const val SUBSCRIPTION_PRODUCT = 1
    const val ONE_TIME_PRODUCT = 2
    const val REFUND_FULL = 1
}

/** The bodies of the pushes that a Pub/Sub subscription sends to the webhook, carrying a Play notification. */
internal object Push {
    fun messageId() = "msg-" + Random.nextLong().toString(Character.MAX_RADIX).removePrefix("-")

    /** The push that carries [notification], a Play notification already written as JSON text. */
    fun of(
        notification: String,
        messageId: String = messageId(),
    ): String {
        val data = Base64.getEncoder().encodeToString(notification.toByteArray())
        return """{"message":{"data":"$data","messageId":"$messageId","publishTime":"2026-01-01T00:00:00Z"},""" +
            """"subscription":"projects/conformance/subscriptions/rtdn"}"""
    }

    private fun envelope(
        content: String,
        packageName: String,
    ) = """{"version":"1.0","packageName":"$packageName","eventTimeMillis":"1767225600000",$content}"""

    fun subscription(
        token: String,
        type: Int = NotificationType.RENEWED,
        packageName: String = Fixture.PACKAGE_NAME,
    ) = envelope(
        """"subscriptionNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"$token",""" +
            """"subscriptionId":"premium_monthly"}""",
        packageName,
    )

    fun voided(
        token: String,
        productType: Int = NotificationType.SUBSCRIPTION_PRODUCT,
    ) = envelope(
        """"voidedPurchaseNotification":{"purchaseToken":"$token","orderId":"GPA.1","productType":$productType,""" +
            """"refundType":${NotificationType.REFUND_FULL}}""",
        Fixture.PACKAGE_NAME,
    )

    fun oneTime(
        token: String,
        type: Int = NotificationType.ONE_TIME_CANCELED,
    ) = envelope(
        """"oneTimeProductNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"$token",""" +
            """"sku":"lifetime_unlock"}""",
        Fixture.PACKAGE_NAME,
    )

    fun test() = envelope(""""testNotification":{"version":"1.0"}""", Fixture.PACKAGE_NAME)
}

/** A push token that is valid, or broken in the ways of [changes] (see [PushSigner]). */
internal fun Context.pushToken(vararg changes: Pair<String, Any>): String {
    val spec =
        buildMap<String, Any> {
            put("audience", Fixture.PUBSUB_AUDIENCE)
            put("email", Fixture.PUBSUB_EMAIL)
            putAll(changes)
        }
    return fake.pushToken(
        JsonObject(
            spec.mapValues { (_, value) ->
                when (value) {
                    is Boolean -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            },
        ),
    )
}

/** Pub/Sub pushes [body] to the webhook with [token] as proof (no proof when it is `null`). */
internal fun Context.webhook(
    body: String,
    token: String? = pushToken(),
): Reply = backend.post("/v1/webhooks/google", body, key = token)
