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

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import dev.pedrorau.kuotakit.backend.domain.PlayOfferPhase
import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionState
import dev.pedrorau.kuotakit.backend.domain.PurchaseInvalidException
import dev.pedrorau.kuotakit.backend.domain.StoreUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.time.Instant

private const val BASE =
    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.acme.app/purchases"

private val ACTIVE_TRIAL =
    """
    {
      "kind": "androidpublisher#subscriptionPurchaseV2",
      "startTime": "2026-10-01T10:00:00.123Z",
      "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
      "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
      "externalAccountIdentifiers": { "obfuscatedExternalAccountId": "anon_buyer" },
      "lineItems": [{
        "productId": "premium_monthly",
        "expiryTime": "2026-11-08T10:00:00Z",
        "autoRenewingPlan": { "autoRenewEnabled": true },
        "offerDetails": { "basePlanId": "monthly" },
        "offerPhase": { "freeTrial": {} }
      }]
    }
    """.trimIndent()

private class Google(
    private val status: Int = 200,
    private val body: String = ACTIVE_TRIAL,
    private val failure: IOException? = null,
) {
    val requests = mutableListOf<String>()

    val api =
        GooglePlayStoreApi.create(
            object : MockHttpTransport() {
                override fun buildRequest(
                    method: String,
                    url: String,
                ): MockLowLevelHttpRequest =
                    object : MockLowLevelHttpRequest(url) {
                        override fun execute(): LowLevelHttpResponse {
                            requests += "$method $url"
                            failure?.let { throw it }
                            return MockLowLevelHttpResponse()
                                .setStatusCode(status)
                                .setContentType("application/json")
                                .setContent(body)
                        }
                    }
            },
            HttpRequestInitializer { },
            "com.acme.app",
        )
}

private fun error(code: Int) = """{"error":{"code":$code,"message":"nope","errors":[{"reason":"x"}]}}"""

class GooglePlayStoreApiTest :
    StringSpec({
        "a subscription is read with subscriptionsv2 and turned into the domain's types" {
            val google = Google()
            val subscription = google.api.subscription("tok/en 1")
            google.requests shouldBe listOf("GET $BASE/subscriptionsv2/tokens/tok%2Fen%201")
            subscription.state shouldBe PlaySubscriptionState.ACTIVE
            subscription.startTime shouldBe Instant.parse("2026-10-01T10:00:00.123Z")
            subscription.acknowledged shouldBe false
            subscription.obfuscatedAccountId shouldBe "anon_buyer"
            val item = subscription.items.single()
            item.productId shouldBe "premium_monthly"
            item.expiryTime shouldBe Instant.parse("2026-11-08T10:00:00Z")
            item.autoRenewing shouldBe true
            item.offerPhase shouldBe PlayOfferPhase.FREE_TRIAL
        }

        "a purchase is acknowledged with the v1 endpoint, which is the only one that can" {
            val google = Google(body = "{}")
            google.api.acknowledge("premium_monthly", "tok1")
            google.requests shouldBe
                listOf("POST $BASE/subscriptions/premium_monthly/tokens/tok1:acknowledge")
        }

        "states, phases and acknowledgement that Google may send are all understood" {
            fun parse(json: String) = Google(body = json).api

            val states =
                mapOf(
                    "SUBSCRIPTION_STATE_PENDING" to PlaySubscriptionState.PENDING,
                    "SUBSCRIPTION_STATE_ACTIVE" to PlaySubscriptionState.ACTIVE,
                    "SUBSCRIPTION_STATE_PAUSED" to PlaySubscriptionState.PAUSED,
                    "SUBSCRIPTION_STATE_IN_GRACE_PERIOD" to PlaySubscriptionState.IN_GRACE_PERIOD,
                    "SUBSCRIPTION_STATE_ON_HOLD" to PlaySubscriptionState.ON_HOLD,
                    "SUBSCRIPTION_STATE_CANCELED" to PlaySubscriptionState.CANCELED,
                    "SUBSCRIPTION_STATE_EXPIRED" to PlaySubscriptionState.EXPIRED,
                    "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED" to PlaySubscriptionState.PENDING_PURCHASE_CANCELED,
                    "SUBSCRIPTION_STATE_UNSPECIFIED" to PlaySubscriptionState.UNKNOWN,
                    "SUBSCRIPTION_STATE_FROM_THE_FUTURE" to PlaySubscriptionState.UNKNOWN,
                )
            states.forEach { (raw, expected) ->
                parse("""{"subscriptionState":"$raw","lineItems":[]}""").subscription("t").state shouldBe expected
            }

            suspend fun phase(json: String) =
                parse(
                    """{"lineItems":[{"productId":"p","offerPhase":$json}]}""",
                ).subscription("t").items.single().offerPhase
            phase("""{"freeTrial":{}}""") shouldBe PlayOfferPhase.FREE_TRIAL
            phase("""{"introductoryPrice":{}}""") shouldBe PlayOfferPhase.INTRODUCTORY_PRICE
            phase("""{"basePrice":{}}""") shouldBe PlayOfferPhase.BASE_PRICE
            phase("""{"prorationPeriod":{}}""") shouldBe PlayOfferPhase.OTHER

            val acknowledged = """{"acknowledgementState":"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED","lineItems":[]}"""
            parse(acknowledged).subscription("t").acknowledged shouldBe true
        }

        "missing or unreadable fields become null or the safe default, never a guess" {
            val bare = Google(body = """{"lineItems":[{"expiryTime":"not a date"}]}""").api.subscription("t")
            bare.state shouldBe PlaySubscriptionState.UNKNOWN
            bare.startTime shouldBe null
            bare.acknowledged shouldBe false
            bare.obfuscatedAccountId shouldBe null
            bare.items.single().expiryTime shouldBe null
            bare.items.single().autoRenewing shouldBe false
            bare.items.single().productId shouldBe ""
            Google(body = "{}").api.subscription("t").items shouldBe emptyList()
        }

        "reading: Google saying the token is unknown is the buyer's problem" {
            listOf(400, 404, 410).forEach { code ->
                shouldThrow<PurchaseInvalidException> {
                    Google(
                        status = code,
                        body = error(code),
                    ).api.subscription("t")
                }
            }
        }

        "acknowledging: any failure is ours or a race, never the buyer's, so the app retries" {
            listOf(400, 404, 410).forEach { code ->
                shouldThrow<StoreUnavailableException> {
                    Google(
                        status = code,
                        body = error(code),
                    ).api.acknowledge("p", "t")
                }
            }
        }

        "any other failure never grants access: credentials, permissions, quota, outage and network" {
            listOf(401, 403, 429, 500, 503).forEach { code ->
                shouldThrow<StoreUnavailableException> {
                    Google(
                        status = code,
                        body = error(code),
                    ).api.subscription("t")
                }
                shouldThrow<StoreUnavailableException> {
                    Google(
                        status = code,
                        body = error(code),
                    ).api.acknowledge("p", "t")
                }
            }
            val down = Google(failure = IOException("connection reset"))
            shouldThrow<StoreUnavailableException> { down.api.subscription("t") }
            shouldThrow<StoreUnavailableException> { down.api.acknowledge("p", "t") }
        }

        "the cause of a failure says the status and Google's reason, but never the request URL with the token" {
            val body = """{"error":{"code":403,"message":"nope","errors":[{"reason":"insufficientPermissions"}]}}"""
            val e =
                shouldThrow<StoreUnavailableException> {
                    Google(
                        status = 403,
                        body = body,
                    ).api.subscription("SECRET-TOKEN")
                }
            e.cause!!.message shouldBe "Google API HTTP 403: insufficientPermissions"
            e.cause!!.cause shouldBe null
            val invalid =
                shouldThrow<PurchaseInvalidException> {
                    Google(
                        status = 404,
                        body = error(404),
                    ).api.subscription("SECRET-TOKEN")
                }
            invalid.cause!!.message!!.contains("SECRET-TOKEN") shouldBe false
        }

        "an error message never carries the purchase token" {
            val e =
                shouldThrow<PurchaseInvalidException> {
                    Google(
                        status = 404,
                        body = error(404),
                    ).api.subscription("SECRET-TOKEN")
                }
            e.message!!.contains("SECRET-TOKEN") shouldBe false
            val u =
                shouldThrow<StoreUnavailableException> {
                    Google(
                        status = 503,
                        body = error(503),
                    ).api.subscription("SECRET-TOKEN")
                }
            u.message!!.contains("SECRET-TOKEN") shouldBe false
        }

        "the token of the purchase this one replaced is read, and an empty one is none" {
            val withLink = ACTIVE_TRIAL.replaceFirst("{", """{"linkedPurchaseToken":"OLD_TOKEN",""")
            Google(body = withLink).api.subscription("t").linkedPurchaseToken shouldBe "OLD_TOKEN"
            val empty = ACTIVE_TRIAL.replaceFirst("{", """{"linkedPurchaseToken":"",""")
            Google(body = empty).api.subscription("t").linkedPurchaseToken shouldBe null
            Google().api.subscription("t").linkedPurchaseToken shouldBe null
        }
    })
