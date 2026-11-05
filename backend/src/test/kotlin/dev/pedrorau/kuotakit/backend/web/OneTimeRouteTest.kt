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

package dev.pedrorau.kuotakit.backend.web

import dev.pedrorau.kuotakit.backend.domain.CONFIG
import dev.pedrorau.kuotakit.backend.domain.EntitlementDefinition
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimeState
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.backend.domain.PushTokenVerifier
import dev.pedrorau.kuotakit.backend.google.pubSubPush
import dev.pedrorau.kuotakit.backend.module
import dev.pedrorau.kuotakit.backend.testing.ANDROID_PACKAGE
import dev.pedrorau.kuotakit.backend.testing.APP_KEY
import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.OpenApiContract
import dev.pedrorau.kuotakit.backend.testing.playOneTime
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.backend.testing.testServices
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

private const val USER = "anon_1"
private const val TOKEN = "one-time-token"

/** The test configuration plus a lifetime unlock bought once. */
private val WITH_LIFETIME =
    CONFIG.copy(
        entitlements =
            CONFIG.entitlements +
                ("lifetime" to EntitlementDefinition(mapOf(Store.PLAY_STORE to setOf("lifetime_unlock")))),
        oneTimeProducts = mapOf(Store.PLAY_STORE to setOf("lifetime_unlock")),
    )

private class OneTimeBackend {
    val google = FakePlayStoreApi()
    val repository = InMemoryPurchaseRepository()
    val verifier =
        object : PushTokenVerifier {
            override suspend fun verify(bearerToken: String) = bearerToken == "good-token"
        }
}

private fun withBackend(block: suspend ApplicationTestBuilder.(OneTimeBackend) -> Unit) =
    testApplication {
        val backend = OneTimeBackend()
        application {
            module(
                testServices(
                    backend.repository,
                    backend.google,
                    InMemoryProcessedEvents(),
                    backend.verifier,
                    WITH_LIFETIME,
                ),
            )
        }
        block(backend)
    }

private suspend fun ApplicationTestBuilder.submit(
    productId: String,
    token: String,
): HttpResponse =
    client.post("/v1/subscribers/$USER/receipts") {
        header(HttpHeaders.Authorization, "Bearer $APP_KEY")
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
            kuotaKitJson.encodeToString(
                ReceiptRequest.serializer(),
                PlayStoreReceiptRequest(ANDROID_PACKAGE, productId, token),
            ),
        )
    }

private suspend fun ApplicationTestBuilder.customer(): CustomerInfo =
    kuotaKitJson.decodeFromString(
        client.get("/v1/subscribers/$USER") { header(HttpHeaders.Authorization, "Bearer $APP_KEY") }.bodyAsText(),
    )

private suspend fun ApplicationTestBuilder.webhook(
    notification: String,
    id: String,
) = client.post("/v1/webhooks/google") {
    header(HttpHeaders.Authorization, "Bearer good-token")
    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    setBody(pubSubPush(notification, id))
}

private fun oneTimeNotification() =
    """{"packageName":"$ANDROID_PACKAGE","oneTimeProductNotification":""" +
        """{"version":"1.0","notificationType":2,"purchaseToken":"$TOKEN","sku":"lifetime_unlock"}}"""

private fun voided() =
    """{"packageName":"$ANDROID_PACKAGE","voidedPurchaseNotification":""" +
        """{"purchaseToken":"$TOKEN","orderId":"GS.1","productType":2,"refundType":1}}"""

class OneTimeRouteTest :
    StringSpec({
        "a lifetime unlock is validated as a one-time purchase and shows up as an entitlement and a purchase" {
            withBackend { backend ->
                val response = submit("lifetime_unlock", TOKEN)
                response.status shouldBe HttpStatusCode.OK
                OpenApiContract.assertResponseMatches(HttpMethod.Post, "/v1/subscribers/$USER/receipts", response)
                val info = kuotaKitJson.decodeFromString<CustomerInfo>(response.bodyAsText())
                info.entitlements["lifetime"]?.isActive shouldBe true
                info.entitlements["lifetime"]?.expiresAt shouldBe null
                info.nonSubscriptionPurchases.map { it.productId } shouldBe listOf("lifetime_unlock")
                info.activeSubscriptions shouldBe emptyList()
                backend.google.calls.map { it.substringBefore(':') } shouldBe listOf("getOneTime", "acknowledgeOneTime")
            }
        }

        "cancelling it, from a notification, takes the access away" {
            withBackend { backend ->
                backend.google.oneTime = { playOneTime(acknowledged = true) }
                submit("lifetime_unlock", TOKEN)
                backend.google.oneTime = { playOneTime(state = PlayOneTimeState.CANCELLED, acknowledged = true) }
                webhook(oneTimeNotification(), "m-cancel").status shouldBe HttpStatusCode.NoContent
                customer().entitlements["lifetime"]?.isActive shouldBe false
                customer().nonSubscriptionPurchases shouldBe emptyList()
                backend.repository
                    .purchasesOf(USER)
                    .single()
                    .status shouldBe PurchaseStatus.REVOKED
            }
        }

        "a refund takes the access away for good, even if the receipt is sent again" {
            withBackend { backend ->
                backend.google.oneTime = { playOneTime(acknowledged = true) }
                submit("lifetime_unlock", TOKEN)
                webhook(voided(), "m-refund").status shouldBe HttpStatusCode.NoContent
                customer().entitlements["lifetime"]?.isActive shouldBe false
                submit("lifetime_unlock", TOKEN).status shouldBe HttpStatusCode.OK
                customer().entitlements["lifetime"]?.isActive shouldBe false
            }
        }

        "a payment still pending is a 402 and gives nothing" {
            withBackend { backend ->
                backend.google.oneTime = { playOneTime(state = PlayOneTimeState.PENDING) }
                submit("lifetime_unlock", TOKEN).status shouldBe HttpStatusCode.PaymentRequired
                customer().entitlements["lifetime"] shouldBe null
            }
        }

        "upgrading ends the old plan and gives the new one" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submit("premium_monthly", "old-token")
                customer().activeSubscriptions shouldBe listOf("premium_monthly")

                backend.google.subscription = {
                    playSubscription(
                        productId = "premium_annual",
                        acknowledged = true,
                        linkedPurchaseToken = "old-token",
                    )
                }
                submit("premium_annual", "new-token").status shouldBe HttpStatusCode.OK

                val info = customer()
                info.activeSubscriptions shouldBe listOf("premium_annual")
                info.entitlements["premium"]?.productId shouldBe "premium_annual"
                backend.repository
                    .purchasesOf(USER)
                    .first { it.token == "old-token" }
                    .status shouldBe
                    PurchaseStatus.EXPIRED
            }
        }
    })
