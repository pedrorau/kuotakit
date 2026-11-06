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

import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionState
import dev.pedrorau.kuotakit.backend.domain.PurchaseInvalidException
import dev.pedrorau.kuotakit.backend.domain.StoreUnavailableException
import dev.pedrorau.kuotakit.backend.module
import dev.pedrorau.kuotakit.backend.testing.ANDROID_PACKAGE
import dev.pedrorau.kuotakit.backend.testing.APP_KEY
import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.OpenApiContract
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.backend.testing.testServices
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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

private const val TOKEN = "SECRET-purchase-token-123"

private fun receipt(
    token: String = TOKEN,
    packageName: String = ANDROID_PACKAGE,
) = kuotaKitJson.encodeToString(
    ReceiptRequest.serializer(),
    PlayStoreReceiptRequest(packageName = packageName, productId = "premium_monthly", purchaseToken = token),
)

private class Backend(
    val google: FakePlayStoreApi? = FakePlayStoreApi(),
    val repository: InMemoryPurchaseRepository = InMemoryPurchaseRepository(),
)

private fun withBackend(
    backend: Backend = Backend(),
    block: suspend ApplicationTestBuilder.(Backend) -> Unit,
) = testApplication {
    application { module(testServices(backend.repository, backend.google)) }
    block(backend)
}

private suspend fun ApplicationTestBuilder.post(
    body: String = receipt(),
    user: String = "anon_1",
    key: String? = APP_KEY,
): HttpResponse =
    client.post("/v1/subscribers/$user/receipts") {
        key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(body)
    }

private suspend fun HttpResponse.error(): ErrorCode =
    kuotaKitJson.decodeFromString<ErrorResponse>(bodyAsText()).error.code

private suspend fun HttpResponse.shouldMatchTheContract() =
    OpenApiContract.assertResponseMatches(HttpMethod.Post, "/v1/subscribers/anon_1/receipts", this)

class ReceiptRouteTest :
    StringSpec({
        "a valid purchase is saved and the answer says what the user can now use" {
            withBackend { backend ->
                val response = post()
                response.status shouldBe HttpStatusCode.OK
                response.shouldMatchTheContract()
                val info = kuotaKitJson.decodeFromString<CustomerInfo>(response.bodyAsText())
                info.appUserId shouldBe "anon_1"
                info.entitlements["premium"]?.isActive shouldBe true
                info.activeSubscriptions shouldBe listOf("premium_monthly")
                backend.google!!.calls.map { it.substringBefore(':') } shouldBe listOf("get", "acknowledge")
            }
        }

        "sending the same purchase twice gives the same answer" {
            withBackend { backend ->
                backend.google!!.subscription = { playSubscription(acknowledged = true) }
                val first = post().bodyAsText()
                post().bodyAsText() shouldBe first
            }
        }

        "without a valid key nothing is read, asked or saved" {
            withBackend { backend ->
                listOf(null, "pk_wrong_wrong_wrong").forEach { key ->
                    val response = post(key = key)
                    response.status shouldBe HttpStatusCode.Unauthorized
                    response.error() shouldBe ErrorCode.UNAUTHORIZED
                    response.shouldMatchTheContract()
                }
                backend.google!!.calls shouldBe emptyList()
                backend.repository.purchasesOf("anon_1") shouldBe emptyList()
            }
        }

        "a malformed body or user id is a 400 that never reaches Google" {
            withBackend { backend ->
                listOf(
                    "not json",
                    "{}",
                    """{"store":"play_store"}""",
                    """{"store":"carrier_pigeon","purchase_token":"x"}""",
                    """{"store":"play_store","package_name":"$ANDROID_PACKAGE","product_id":"p"}""",
                ).forEach { body ->
                    val response = post(body)
                    withClue(body) { response.status shouldBe HttpStatusCode.BadRequest }
                    response.error() shouldBe ErrorCode.INVALID_PAYLOAD
                    response.shouldMatchTheContract()
                }
                post(user = "bad id").status shouldBe HttpStatusCode.BadRequest
                post(user = "..").status shouldBe HttpStatusCode.BadRequest
                backend.google!!.calls shouldBe emptyList()
            }
        }

        "a receipt for another app is a 400" {
            withBackend {
                val response = post(receipt(packageName = "com.evil.app"))
                response.status shouldBe HttpStatusCode.BadRequest
                response.error() shouldBe ErrorCode.INVALID_PAYLOAD
                response.shouldMatchTheContract()
            }
        }

        "a purchase the store rejects is a 402 and grants nothing" {
            withBackend { backend ->
                backend.google!!.subscription = { throw PurchaseInvalidException("no such token") }
                val response = post()
                response.status shouldBe HttpStatusCode.PaymentRequired
                response.error() shouldBe ErrorCode.PURCHASE_INVALID
                response.shouldMatchTheContract()
                backend.repository.purchasesOf("anon_1") shouldBe emptyList()
            }
        }

        "a pending or held purchase is not granted" {
            withBackend { backend ->
                backend.google!!.subscription = { playSubscription(state = PlaySubscriptionState.PENDING) }
                post().status shouldBe HttpStatusCode.PaymentRequired
                backend.google.subscription = { playSubscription(state = PlaySubscriptionState.ON_HOLD) }
                val info = kuotaKitJson.decodeFromString<CustomerInfo>(post().bodyAsText())
                info.entitlements["premium"]?.isActive shouldBe false
                info.activeSubscriptions shouldBe emptyList()
            }
        }

        "a token that belongs to another user is a 409 and the owner keeps it" {
            withBackend { backend ->
                val google = backend.google!!
                google.subscription = { playSubscription(obfuscatedAccountId = playObfuscatedAccountId("owner")) }
                post(user = "owner").status shouldBe HttpStatusCode.OK
                // Google still says the purchase is the owner's
                val response = post(user = "thief")
                response.status shouldBe HttpStatusCode.Conflict
                response.error() shouldBe ErrorCode.TOKEN_ALREADY_LINKED
                backend.repository.purchasesOf("thief") shouldBe emptyList()
                backend.repository.purchasesOf("owner").size shouldBe 1
            }
        }

        "if Google cannot be reached the answer is a 502 and access is not granted" {
            withBackend { backend ->
                backend.google!!.subscription = { throw StoreUnavailableException("down") }
                val response = post()
                response.status shouldBe HttpStatusCode.BadGateway
                response.error() shouldBe ErrorCode.STORE_UNAVAILABLE
                response.shouldMatchTheContract()
                backend.repository.purchasesOf("anon_1") shouldBe emptyList()
            }
        }

        "if the acknowledgement fails nothing is saved and the app can retry" {
            withBackend { backend ->
                backend.google!!.acknowledgeFailure = StoreUnavailableException("down")
                post().status shouldBe HttpStatusCode.BadGateway
                backend.repository.purchasesOf("anon_1") shouldBe emptyList()
                backend.google.acknowledgeFailure = null
                post().status shouldBe HttpStatusCode.OK
            }
        }

        "a backend without Google Play configured answers 502, never an empty success" {
            withBackend(Backend(google = null)) {
                val response = post()
                response.status shouldBe HttpStatusCode.BadGateway
                response.error() shouldBe ErrorCode.STORE_UNAVAILABLE
            }
        }

        "no error answer ever contains the purchase token" {
            withBackend { backend ->
                val bodies = mutableListOf<String>()
                bodies += post(receipt(packageName = "com.evil.app")).bodyAsText()
                backend.google!!.subscription = { throw PurchaseInvalidException("no such token") }
                bodies += post().bodyAsText()
                backend.google.subscription = { throw StoreUnavailableException("down") }
                bodies += post().bodyAsText()
                backend.google.subscription = { playSubscription(obfuscatedAccountId = "anon_buyer") }
                bodies += post(user = "anon_other").bodyAsText()
                bodies.forEach { it shouldNotContain TOKEN }
            }
        }

        "a body over 16 KiB is refused before it is parsed, and Google is not asked" {
            withBackend { backend ->
                val huge = receipt(token = "t".repeat(20_000))
                val response = post(huge)
                response.status shouldBe HttpStatusCode.BadRequest
                response.error() shouldBe ErrorCode.INVALID_PAYLOAD
                response.shouldMatchTheContract()
                backend.google!!.calls shouldBe emptyList()
            }
        }

        "a body just under the limit is still read" {
            withBackend { backend ->
                post(receipt(token = "t".repeat(4096))).status shouldBe HttpStatusCode.OK
                backend.google!!.calls.size shouldBe 2
            }
        }
    })
