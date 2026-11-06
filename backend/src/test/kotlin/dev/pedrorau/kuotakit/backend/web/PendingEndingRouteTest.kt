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

import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.backend.domain.PushTokenVerifier
import dev.pedrorau.kuotakit.backend.google.pubSubPush
import dev.pedrorau.kuotakit.backend.module
import dev.pedrorau.kuotakit.backend.testing.ANDROID_PACKAGE
import dev.pedrorau.kuotakit.backend.testing.APP_KEY
import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.backend.testing.testServices
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

private const val USER = "anon_1"
private const val TOKEN = "early-refund-token"

private val verifier =
    object : PushTokenVerifier {
        override suspend fun verify(bearerToken: String) = bearerToken == "good-token"
    }

private fun withBackend(block: suspend ApplicationTestBuilder.(InMemoryPurchaseRepository, FakePlayStoreApi) -> Unit) =
    testApplication {
        val repository = InMemoryPurchaseRepository()
        val google = FakePlayStoreApi()
        application { module(testServices(repository, google, InMemoryProcessedEvents(), verifier)) }
        block(repository, google)
    }

private suspend fun ApplicationTestBuilder.refundNotification() =
    client.post("/v1/webhooks/google") {
        header(HttpHeaders.Authorization, "Bearer good-token")
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
            pubSubPush(
                """{"packageName":"$ANDROID_PACKAGE","voidedPurchaseNotification":""" +
                    """{"purchaseToken":"$TOKEN","orderId":"GS.1","productType":1,"refundType":1}}""",
                "m-early",
            ),
        )
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

class PendingEndingRouteTest :
    StringSpec({
        "a purchase refunded before its receipt arrives never gives access, even if Google still shows it active" {
            withBackend { repository, google ->
                refundNotification().status shouldBe HttpStatusCode.NoContent
                google.subscription = { playSubscription(acknowledged = false) }

                val response = submitReceipt()

                response.status shouldBe HttpStatusCode.OK
                val info = kuotaKitJson.decodeFromString<CustomerInfo>(response.bodyAsText())
                info.entitlements["premium"]?.isActive shouldBe false
                info.activeSubscriptions shouldBe emptyList()
                repository.purchasesOf(USER).single().status shouldBe PurchaseStatus.REFUNDED
                google.calls.none { it.startsWith("acknowledge") } shouldBe true
                // sending it again changes nothing
                submitReceipt().status shouldBe HttpStatusCode.OK
                client.get("/v1/subscribers/$USER") { header(HttpHeaders.Authorization, "Bearer $APP_KEY") }
                repository.purchasesOf(USER).single().status shouldBe PurchaseStatus.REFUNDED
            }
        }
    })
