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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

/** The envelope Pub/Sub pushes, with [notification] (JSON text) as the base64 data. */
fun pubSubPush(
    notification: String,
    messageId: String = "136969346945",
): String {
    val data = Base64.getEncoder().encodeToString(notification.toByteArray())
    return """{"message":{"attributes":{"key":"value"},"data":"$data","messageId":"$messageId"},""" +
        """"subscription":"projects/p/subscriptions/s"}"""
}

private fun subscription(type: Int) =
    """{"version":"1.0","packageName":"com.some.thing","eventTimeMillis":"1503349566168",""" +
        """"subscriptionNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"PURCHASE_TOKEN"}}"""

private fun parse(notification: String) = PubSubPushParser.parse(pubSubPush(notification))

class PubSubPushParserTest :
    StringSpec({
        "a subscription notification, as in Google's example" {
            parse(subscription(4)) shouldBe
                PlayPush("136969346945", "com.some.thing", PlayNotification.SubscriptionChanged("PURCHASE_TOKEN"))
        }

        "every kind of change is read again, except a revocation and the price-only ones" {
            val read = listOf(1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 13, 17, 18, 20, 99)
            read.forEach { type ->
                parse(subscription(type)).notification shouldBe PlayNotification.SubscriptionChanged("PURCHASE_TOKEN")
            }
            parse(subscription(12)).notification shouldBe PlayNotification.SubscriptionRevoked("PURCHASE_TOKEN")
            listOf(8, 19, 22).forEach { type ->
                (parse(subscription(type)).notification is PlayNotification.Ignored) shouldBe true
            }
        }

        "a voided subscription, as in Google's example" {
            val voided =
                """{"version":"1.0","packageName":"com.some.app","eventTimeMillis":"1503349566168",""" +
                    """"voidedPurchaseNotification":{"purchaseToken":"PURCHASE_TOKEN",""" +
                    """"orderId":"GS.0000-0000-0000","productType":1,"refundType":1}}"""
            parse(voided) shouldBe
                PlayPush("136969346945", "com.some.app", PlayNotification.PurchaseVoided("PURCHASE_TOKEN"))
        }

        "a voided one-time purchase is a refund, unless only some units were refunded" {
            fun voided(refundType: Int) =
                """{"packageName":"com.some.app","voidedPurchaseNotification":""" +
                    """{"purchaseToken":"T","orderId":"O","productType":2,"refundType":$refundType}}"""
            parse(voided(1)).notification shouldBe PlayNotification.PurchaseVoided("T")
            parse(voided(2)).notification shouldBe PlayNotification.OneTimeChanged("T")
        }

        "a voided purchase of a type this backend does not know is ignored" {
            val voided =
                """{"packageName":"p","voidedPurchaseNotification":{"purchaseToken":"T","productType":9,"refundType":1}}"""
            (parse(voided).notification is PlayNotification.Ignored) shouldBe true
        }

        "a one-time product notification, bought or cancelled, is read again" {
            listOf(1, 2, 99).forEach { type ->
                val oneTime =
                    """{"packageName":"p","oneTimeProductNotification":""" +
                        """{"version":"1.0","notificationType":$type,"purchaseToken":"T","sku":"lifetime"}}"""
                parse(oneTime).notification shouldBe PlayNotification.OneTimeChanged("T")
            }
        }

        "test, pending refund review and unknown notifications are ignored, not errors" {
            listOf(
                """{"version":"1.0","packageName":"com.some.thing","testNotification":{"version":"1.0"}}""",
                """{"packageName":"p","pendingRefundReviewNotification":{"pendingRefundToken":"x"}}""",
                """{"packageName":"p","somethingNew":{}}""",
            ).forEach { notification ->
                val parsed = parse(notification)
                (parsed.notification is PlayNotification.Ignored) shouldBe true
                parsed.packageName.isNotBlank() shouldBe true
            }
        }

        "the notification type may arrive as a string number" {
            val asText =
                """{"packageName":"p","subscriptionNotification":{"notificationType":"12","purchaseToken":"T"}}"""
            parse(asText).notification shouldBe PlayNotification.SubscriptionRevoked("T")
        }

        "base64 with line breaks is accepted" {
            val data = Base64.getMimeEncoder(4, "\n".toByteArray()).encodeToString(subscription(4).toByteArray())
            val body = """{"message":{"data":${"\"" + data.replace("\n", "\\n") + "\""},"messageId":"1"}}"""
            parsed(body).notification shouldBe PlayNotification.SubscriptionChanged("PURCHASE_TOKEN")
        }

        "anything that is not a push of a Play notification is refused" {
            listOf(
                "",
                "not json",
                "[]",
                "{}",
                """{"message":"text"}""",
                """{"message":{}}""",
                """{"message":{"data":"AAAA","messageId":"1"}}""",
                """{"message":{"data":"!!!","messageId":"1"}}""",
                """{"message":{"messageId":"1"}}""",
                pubSubPush(subscription(4), messageId = ""),
                pubSubPush("[]"),
                pubSubPush("""{"subscriptionNotification":{"notificationType":4,"purchaseToken":"T"}}"""),
                pubSubPush("""{"packageName":"p","subscriptionNotification":{"notificationType":4}}"""),
                pubSubPush("""{"packageName":"p","subscriptionNotification":{"purchaseToken":"T"}}"""),
                pubSubPush(
                    """{"packageName":"p","subscriptionNotification":{"notificationType":4,"purchaseToken":""}}""",
                ),
                pubSubPush("""{"packageName":"p","subscriptionNotification":"x"}"""),
                pubSubPush("""{"packageName":"p","voidedPurchaseNotification":{"purchaseToken":"T"}}"""),
            ).forEach { body ->
                shouldThrow<InvalidNotificationException> { PubSubPushParser.parse(body) }
            }
        }

        "a deeply nested body is refused, it does not crash the server" {
            shouldThrow<InvalidNotificationException> { PubSubPushParser.parse("[".repeat(10_000)) }
            shouldThrow<InvalidNotificationException> { PubSubPushParser.parse("""{"message":""" + "[".repeat(10_000)) }
        }
    })

private fun parsed(body: String) = PubSubPushParser.parse(body)
