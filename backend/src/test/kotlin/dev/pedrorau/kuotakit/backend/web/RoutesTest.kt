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
import dev.pedrorau.kuotakit.backend.domain.NOW
import dev.pedrorau.kuotakit.backend.domain.Purchase
import dev.pedrorau.kuotakit.backend.domain.PurchaseKind
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.domain.purchase
import dev.pedrorau.kuotakit.backend.domain.toOfferingsResponse
import dev.pedrorau.kuotakit.backend.module
import dev.pedrorau.kuotakit.backend.testing.APP_KEY
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.OpenApiContract
import dev.pedrorau.kuotakit.backend.testing.SECRET_KEY
import dev.pedrorau.kuotakit.backend.testing.testServices
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.days

private fun bearer(key: String) = "Bearer $key"

/** Runs [block] against the real routes with fakes behind them. */
private fun withBackend(
    repository: PurchaseRepository = InMemoryPurchaseRepository(),
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    application { module(testServices(repository)) }
    block()
}

private suspend fun ApplicationTestBuilder.get(
    path: String,
    authorization: String? = null,
): HttpResponse = client.get(path) { authorization?.let { header(HttpHeaders.Authorization, it) } }

private suspend fun HttpResponse.shouldMatchTheContract(
    path: String,
    authorization: String? = null,
) = OpenApiContract.assertMatches(HttpMethod.Get, path, this, authorization)

class RoutesTest :
    StringSpec({
        "health needs no key and matches the contract" {
            withBackend {
                val response = get("/health")
                response.status shouldBe HttpStatusCode.OK
                response.shouldMatchTheContract("/health")
            }
        }

        "offerings come from the configuration, for an app key and for a secret key" {
            withBackend {
                listOf(APP_KEY, SECRET_KEY).forEach { key ->
                    val response = get("/v1/offerings", bearer(key))
                    response.status shouldBe HttpStatusCode.OK
                    response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                    val body = response.bodyAsText()
                    kuotaKitJson.decodeFromString<OfferingsResponse>(body) shouldBe CONFIG.toOfferingsResponse()
                    OpenApiContract.assertMatches(HttpMethod.Get, "/v1/offerings", response, bearer(key))
                }
            }
        }

        "a missing, wrong or malformed key is a 401 with the error body, and never reaches the data" {
            withBackend {
                listOf(
                    null,
                    bearer("pk_wrong_wrong_wrong"),
                    bearer(APP_KEY.dropLast(1)),
                    bearer("${APP_KEY}x"),
                    "Basic $APP_KEY",
                    APP_KEY,
                    "Bearer",
                ).forEach { authorization ->
                    listOf("/v1/offerings", "/v1/subscribers/anon_1").forEach { path ->
                        val response = get(path, authorization)
                        withClue("Authorization: $authorization on $path") {
                            response.status shouldBe
                                HttpStatusCode.Unauthorized
                        }
                        val body = response.bodyAsText()
                        kuotaKitJson.decodeFromString<ErrorResponse>(body).error.code shouldBe ErrorCode.UNAUTHORIZED
                        OpenApiContract.assertResponseMatches(HttpMethod.Get, path, response)
                    }
                }
            }
        }

        "an Authorization header Ktor cannot parse is a 401 too, never a 400 or a way in" {
            withBackend {
                val tab = get("/v1/offerings", "Bearer\t$APP_KEY")
                tab.status shouldBe HttpStatusCode.Unauthorized
                OpenApiContract.assertResponseMatches(HttpMethod.Get, "/v1/offerings", tab)
                get("/v1/offerings", "Bearer a b").status shouldBe HttpStatusCode.Unauthorized
                // two Authorization headers: whichever order, the request is not authenticated
                listOf(
                    listOf(bearer(APP_KEY), bearer("pk_wrong_wrong_wrong")),
                    listOf(bearer("pk_wrong_wrong_wrong"), bearer(APP_KEY)),
                ).forEach { values ->
                    val response =
                        client.get(
                            "/v1/offerings",
                        ) { values.forEach { header(HttpHeaders.Authorization, it) } }
                    response.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }

        "whitespace at the edge of the header is ignored, as HTTP says, but the key must still match exactly" {
            withBackend {
                get("/v1/offerings", "Bearer $APP_KEY ").status shouldBe HttpStatusCode.OK
                get("/v1/offerings", "Bearer ${APP_KEY.uppercase()}").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "a subscriber nobody has bought for is an empty customer info, not an error" {
            withBackend {
                val response = get("/v1/subscribers/anon_new", bearer(APP_KEY))
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                val info = kuotaKitJson.decodeFromString<CustomerInfo>(body)
                info.appUserId shouldBe "anon_new"
                info.requestDate shouldBe NOW
                info.entitlements shouldBe emptyMap()
                info.activeSubscriptions shouldBe emptyList()
                OpenApiContract.assertMatches(HttpMethod.Get, "/v1/subscribers/anon_new", response, bearer(APP_KEY))
            }
        }

        "a subscriber with purchases gets the entitlements computed at the time of the request" {
            val repository = InMemoryPurchaseRepository()
            repository.save(
                "anon_paid",
                purchase(token = "t1", productId = "premium_monthly", periodType = PeriodType.TRIAL),
            )
            repository.save(
                "anon_paid",
                purchase(
                    token = "t2",
                    productId = "pro_lifetime",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    expiresAt = null,
                    autoRenewing = false,
                ),
            )
            repository.save("anon_other", purchase(token = "t3", productId = "premium_annual"))
            withBackend(repository) {
                val response = get("/v1/subscribers/anon_paid", bearer(SECRET_KEY))
                response.status shouldBe HttpStatusCode.OK
                val info = kuotaKitJson.decodeFromString<CustomerInfo>(response.bodyAsText())
                info.entitlements.keys.toList() shouldBe listOf("premium", "lifetime")
                info.entitlements.getValue("premium").isActive shouldBe true
                info.entitlements.getValue("premium").periodType shouldBe PeriodType.TRIAL
                info.entitlements.getValue("premium").expiresAt shouldBe NOW + 10.days
                info.entitlements.getValue("lifetime").expiresAt shouldBe null
                info.activeSubscriptions shouldBe listOf("premium_monthly")
                info.nonSubscriptionPurchases.map { it.productId } shouldBe listOf("pro_lifetime")
                OpenApiContract.assertMatches(HttpMethod.Get, "/v1/subscribers/anon_paid", response, bearer(SECRET_KEY))
            }
        }

        "an app user id the contract does not allow is a 400" {
            withBackend {
                listOf(
                    "has%20space",
                    "a".repeat(129),
                    "semi%3Bcolon",
                    "caf%C3%A9",
                    "slash%2Fname",
                    "..",
                    ".",
                    "...",
                ).forEach { id ->
                    val response = get("/v1/subscribers/$id", bearer(APP_KEY))
                    response.status shouldBe HttpStatusCode.BadRequest
                    kuotaKitJson.decodeFromString<ErrorResponse>(response.bodyAsText()).error.code shouldBe
                        ErrorCode.INVALID_PAYLOAD
                }
            }
        }

        "the ids the contract allows are accepted, including an anonymous one and the longest" {
            withBackend {
                listOf("\$anon_6f1c-ab.cd_ef", "a", "a".repeat(128)).forEach { id ->
                    get("/v1/subscribers/$id", bearer(APP_KEY)).status shouldBe HttpStatusCode.OK
                }
            }
        }

        "an unexpected failure is a 500 that says nothing about the cause and grants nothing" {
            val broken =
                object : PurchaseRepository {
                    override suspend fun purchasesOf(appUserId: String): List<Purchase> =
                        error("jdbc:postgresql://db password=hunter2")

                    override suspend fun save(
                        appUserId: String,
                        purchase: Purchase,
                    ) = error("unused")

                    override suspend fun ownerOf(
                        store: Store,
                        token: String,
                    ): String? = error("unused")
                }
            withBackend(broken) {
                val response = get("/v1/subscribers/anon_1", bearer(APP_KEY))
                response.status shouldBe HttpStatusCode.InternalServerError
                val body = response.bodyAsText()
                body shouldNotContain "hunter2"
                body shouldNotContain "jdbc"
                kuotaKitJson.decodeFromString<ErrorResponse>(body).error.code shouldBe ErrorCode.INTERNAL_ERROR
                OpenApiContract.assertMatches(HttpMethod.Get, "/v1/subscribers/anon_1", response, bearer(APP_KEY))
            }
        }
    })
