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

import dev.pedrorau.kuotakit.backend.domain.NOW
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.backend.domain.PushTokenVerifier
import dev.pedrorau.kuotakit.backend.domain.StoreUnavailableException
import dev.pedrorau.kuotakit.backend.google.pubSubPush
import dev.pedrorau.kuotakit.backend.module
import dev.pedrorau.kuotakit.backend.testing.ANDROID_PACKAGE
import dev.pedrorau.kuotakit.backend.testing.APP_KEY
import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.OpenApiContract
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.backend.testing.testServices
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
import kotlin.time.Duration.Companion.days

private const val GOOD = "good-token"
private const val TOKEN = "SECRET-purchase-token-123"
private const val USER = "anon_1"

private class FakeVerifier : PushTokenVerifier {
    val seen = mutableListOf<String>()

    override suspend fun verify(bearerToken: String): Boolean {
        seen += bearerToken
        if (bearerToken == "keys-down") throw StoreUnavailableException("keys down")
        return bearerToken == GOOD
    }
}

private class WebhookBackend(
    val google: FakePlayStoreApi = FakePlayStoreApi(),
    val repository: InMemoryPurchaseRepository = InMemoryPurchaseRepository(),
    val events: InMemoryProcessedEvents = InMemoryProcessedEvents(),
    val verifier: FakeVerifier? = FakeVerifier(),
)

private fun withBackend(
    backend: WebhookBackend = WebhookBackend(),
    block: suspend ApplicationTestBuilder.(WebhookBackend) -> Unit,
) = testApplication {
    application { module(testServices(backend.repository, backend.google, backend.events, backend.verifier)) }
    block(backend)
}

private fun notification(
    kind: String,
    packageName: String = ANDROID_PACKAGE,
) = """{"version":"1.0","packageName":"$packageName","eventTimeMillis":"1503349566168",$kind}"""

private fun changed(type: Int = 2) =
    notification(
        """"subscriptionNotification":{"version":"1.0","notificationType":$type,"purchaseToken":"$TOKEN"}""",
    )

private fun voided() =
    notification(
        """"voidedPurchaseNotification":{"purchaseToken":"$TOKEN","orderId":"GS.1","productType":1,"refundType":1}""",
    )

private suspend fun ApplicationTestBuilder.push(
    body: String,
    authorization: String? = "Bearer $GOOD",
): HttpResponse =
    client.post("/v1/webhooks/google") {
        authorization?.let { header(HttpHeaders.Authorization, it) }
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(body)
    }

private suspend fun ApplicationTestBuilder.submitReceipt() =
    client.post("/v1/subscribers/$USER/receipts") {
        header(HttpHeaders.Authorization, "Bearer $APP_KEY")
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
            kuotaKitJson.encodeToString(
                ReceiptRequest.serializer(),
                PlayStoreReceiptRequest(ANDROID_PACKAGE, "premium_monthly", TOKEN),
            ),
        )
    }

private suspend fun ApplicationTestBuilder.customer(): CustomerInfo =
    kuotaKitJson.decodeFromString(
        client.get("/v1/subscribers/$USER") { header(HttpHeaders.Authorization, "Bearer $APP_KEY") }.bodyAsText(),
    )

private suspend fun HttpResponse.shouldMatchTheContract() =
    OpenApiContract.assertResponseMatches(HttpMethod.Post, "/v1/webhooks/google", this)

private suspend fun HttpResponse.error() = kuotaKitJson.decodeFromString<ErrorResponse>(bodyAsText()).error.code

class WebhookRouteTest :
    StringSpec({
        "a refund removes access that a purchase had given, end to end" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt().status shouldBe HttpStatusCode.OK
                customer().entitlements["premium"]?.isActive shouldBe true

                val response = push(pubSubPush(voided(), "m-refund"))
                response.status shouldBe HttpStatusCode.NoContent
                response.shouldMatchTheContract()

                customer().entitlements["premium"]?.isActive shouldBe false
                customer().activeSubscriptions shouldBe emptyList()
                backend.repository
                    .purchasesOf(USER)
                    .single()
                    .status shouldBe PurchaseStatus.REFUNDED
            }
        }

        "a renewal extends the access, after Google was asked" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt()
                backend.google.forgetCalls()
                backend.google.subscription = { playSubscription(acknowledged = true, expiryTime = NOW + 60.days) }
                push(pubSubPush(changed(2), "m-renew")).status shouldBe HttpStatusCode.NoContent
                backend.google.calls.size shouldBe 1
                backend.repository
                    .purchasesOf(USER)
                    .single()
                    .expiresAt shouldBe NOW + 60.days
            }
        }

        "the same push delivered twice costs one call to Google" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt()
                backend.google.forgetCalls()
                push(pubSubPush(changed(), "dup")).status shouldBe HttpStatusCode.NoContent
                push(pubSubPush(changed(), "dup")).status shouldBe HttpStatusCode.NoContent
                backend.google.calls.size shouldBe 1
            }
        }

        "a push without a valid proof is a 401 and changes nothing" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt()
                backend.google.forgetCalls()
                listOf(
                    null,
                    "Basic $GOOD",
                    "Bearer",
                    "Bearer ",
                    "Bearer wrong-token",
                    "Bearer $APP_KEY",
                    GOOD,
                ).forEach {
                    val response = push(pubSubPush(voided(), "m-bad"), it)
                    response.status shouldBe HttpStatusCode.Unauthorized
                    response.error() shouldBe ErrorCode.UNAUTHORIZED
                    response.shouldMatchTheContract()
                }
                backend.repository
                    .purchasesOf(USER)
                    .single()
                    .status shouldBe PurchaseStatus.ACTIVE
                backend.google.calls shouldBe emptyList()
            }
        }

        "a backend whose webhook is not configured refuses every push" {
            withBackend(WebhookBackend(verifier = null)) {
                val response = push(pubSubPush(voided()))
                response.status shouldBe HttpStatusCode.Unauthorized
                response.error() shouldBe ErrorCode.UNAUTHORIZED
            }
        }

        "if Google's keys cannot be fetched the answer is a 502 so Pub/Sub retries" {
            withBackend {
                val response = push(pubSubPush(voided()), "Bearer keys-down")
                response.status shouldBe HttpStatusCode.BadGateway
                response.error() shouldBe ErrorCode.STORE_UNAVAILABLE
                response.shouldMatchTheContract()
            }
        }

        "if Google Play cannot be asked the answer is a 502, nothing is remembered and the retry works" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt()
                backend.google.subscription = { throw StoreUnavailableException("down") }
                val response = push(pubSubPush(changed(), "retry"))
                response.status shouldBe HttpStatusCode.BadGateway
                response.error() shouldBe ErrorCode.STORE_UNAVAILABLE
                response.shouldMatchTheContract()
                backend.events.seen(Store.PLAY_STORE, "retry") shouldBe false
                backend.google.subscription = { playSubscription(acknowledged = true) }
                push(pubSubPush(changed(), "retry")).status shouldBe HttpStatusCode.NoContent
            }
        }

        "an authenticated push that can never be read is acknowledged, not retried for a week" {
            withBackend { backend ->
                val garbage = listOf("", "not json", "{}", """{"message":{"data":"AAAA","messageId":"1"}}""")
                garbage.forEach { body ->
                    val response = push(body)
                    response.status shouldBe HttpStatusCode.NoContent
                    response.shouldMatchTheContract()
                }
                push("x".repeat(20_000)).status shouldBe HttpStatusCode.NoContent
                backend.google.calls shouldBe emptyList()
            }
        }

        "garbage without a valid proof is still a 401, never an acknowledgement" {
            withBackend {
                push("x".repeat(20_000), "Bearer nope").status shouldBe HttpStatusCode.Unauthorized
                push("not json", null).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "a push just under 16 KiB is read and applied, one over is dropped" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt()
                val padded = { size: Int ->
                    voided().dropLast(1) + ""","padding":"${"x".repeat(size)}"}"""
                }
                // the envelope adds base64 growth (4/3) and some fixed text: stay clearly under, then clearly over
                push(pubSubPush(padded(5_000), "under")).status shouldBe HttpStatusCode.NoContent
                backend.repository
                    .purchasesOf(USER)
                    .single()
                    .status shouldBe PurchaseStatus.REFUNDED
            }
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt()
                val over = voided().dropLast(1) + ""","padding":"${"x".repeat(20_000)}"}"""
                push(pubSubPush(over, "over")).status shouldBe HttpStatusCode.NoContent
                backend.repository
                    .purchasesOf(USER)
                    .single()
                    .status shouldBe PurchaseStatus.ACTIVE
            }
        }

        "sending the receipt again after a refund does not give the access back" {
            withBackend { backend ->
                backend.google.subscription = { playSubscription(acknowledged = true) }
                submitReceipt()
                push(pubSubPush(voided(), "refund")).status shouldBe HttpStatusCode.NoContent
                // Google still describes it as an active subscription (nothing says it stops doing so at once)
                submitReceipt().status shouldBe HttpStatusCode.OK
                customer().entitlements["premium"]?.isActive shouldBe false
                backend.repository
                    .purchasesOf(USER)
                    .single()
                    .status shouldBe PurchaseStatus.REFUNDED
            }
        }

        "what cannot succeed is acknowledged so Pub/Sub does not retry it for a week" {
            withBackend { backend ->
                val test = notification(""""testNotification":{"version":"1.0"}""")
                val otherApp = changed().replace(ANDROID_PACKAGE, "com.other.app")
                val unknown = changed()
                listOf(test, otherApp, unknown).forEachIndexed { i, body ->
                    val response = push(pubSubPush(body, "m$i"))
                    response.status shouldBe HttpStatusCode.NoContent
                    response.shouldMatchTheContract()
                }
                backend.google.calls shouldBe emptyList()
                backend.repository.ownerOf(Store.PLAY_STORE, TOKEN) shouldBe null
            }
        }

        "no answer ever contains the purchase token" {
            withBackend { backend ->
                val bodies = mutableListOf<String>()
                bodies += push(pubSubPush(voided()), "Bearer nope").bodyAsText()
                bodies += push("garbage $TOKEN").bodyAsText()
                backend.google.subscription = { throw StoreUnavailableException("down $TOKEN") }
                bodies += push(pubSubPush(changed(), "x")).bodyAsText()
                bodies.forEach { it shouldNotContain TOKEN }
            }
        }
    })
