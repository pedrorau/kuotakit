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
import dev.pedrorau.kuotakit.backend.module
import dev.pedrorau.kuotakit.backend.testing.ANDROID_PACKAGE
import dev.pedrorau.kuotakit.backend.testing.APP_KEY
import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.OpenApiContract
import dev.pedrorau.kuotakit.backend.testing.playOneTime
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.backend.testing.testServices
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
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

private fun withBackend(block: suspend ApplicationTestBuilder.(FakePlayStoreApi, InMemoryPurchaseRepository) -> Unit) =
    testApplication {
        val google = FakePlayStoreApi()
        val repository = InMemoryPurchaseRepository()
        application { module(testServices(repository, google)) }
        block(google, repository)
    }

/** The same, with a lifetime unlock bought once. */
private fun withLifetime(block: suspend ApplicationTestBuilder.(FakePlayStoreApi, InMemoryPurchaseRepository) -> Unit) =
    testApplication {
        val google = FakePlayStoreApi()
        val repository = InMemoryPurchaseRepository()
        val config =
            CONFIG.copy(
                entitlements =
                    CONFIG.entitlements +
                        ("lifetime" to EntitlementDefinition(mapOf(Store.PLAY_STORE to setOf("lifetime_unlock")))),
                oneTimeProducts = mapOf(Store.PLAY_STORE to setOf("lifetime_unlock")),
            )
        application { module(testServices(repository, google, entitlements = config)) }
        block(google, repository)
    }

private suspend fun ApplicationTestBuilder.submit(
    user: String,
    productId: String,
): HttpResponse =
    client.post("/v1/subscribers/$user/receipts") {
        header(HttpHeaders.Authorization, "Bearer $APP_KEY")
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
            kuotaKitJson.encodeToString(
                ReceiptRequest.serializer(),
                PlayStoreReceiptRequest(ANDROID_PACKAGE, productId, TOKEN),
            ),
        )
    }

private suspend fun HttpResponse.code() = kuotaKitJson.decodeFromString<ErrorResponse>(bodyAsText()).error.code

/** A purchase must say whose it is (ADR 0017): the account id the app attaches is the hash of the app user id. */
class AccountIdRouteTest :
    StringSpec({
        "a subscription with no account id is a 402 that stores and acknowledges nothing" {
            withBackend { google, repository ->
                google.subscription = { playSubscription(obfuscatedAccountId = null) }
                val response = submit("anon_1", "premium_monthly")
                response.status shouldBe HttpStatusCode.PaymentRequired
                response.code() shouldBe ErrorCode.PURCHASE_INVALID
                OpenApiContract.assertResponseMatches(HttpMethod.Post, "/v1/subscribers/anon_1/receipts", response)
                response.bodyAsText() shouldNotContain TOKEN
                repository.purchasesOf("anon_1") shouldBe emptyList()
                google.calls.none { it.startsWith("acknowledge") } shouldBe true
            }
        }

        "so is a one-time purchase with no account id" {
            withLifetime { google, repository ->
                google.oneTime = { playOneTime(obfuscatedAccountId = null) }
                val response = submit("anon_1", "lifetime_unlock")
                response.status shouldBe HttpStatusCode.PaymentRequired
                response.code() shouldBe ErrorCode.PURCHASE_INVALID
                repository.purchasesOf("anon_1") shouldBe emptyList()
                google.calls.none { it.startsWith("acknowledge") } shouldBe true
            }
        }

        "a one-time purchase that carries the account id of its buyer is accepted, and of another user is a 409" {
            withLifetime { google, repository ->
                google.oneTime = { playOneTime(obfuscatedAccountId = playObfuscatedAccountId("anon_other")) }
                submit("anon_1", "lifetime_unlock").status shouldBe HttpStatusCode.Conflict
                google.oneTime = { playOneTime(acknowledged = true) }
                submit("anon_1", "lifetime_unlock").status shouldBe HttpStatusCode.OK
                repository.purchasesOf("anon_1").size shouldBe 1
            }
        }

        "an account id that is another user's is a 409, and the plain user id is not an account id" {
            withBackend { google, repository ->
                google.subscription = { playSubscription(obfuscatedAccountId = playObfuscatedAccountId("anon_other")) }
                val other = submit("anon_1", "premium_monthly")
                other.status shouldBe HttpStatusCode.Conflict
                other.code() shouldBe ErrorCode.TOKEN_ALREADY_LINKED
                google.subscription = { playSubscription(obfuscatedAccountId = "anon_1") }
                submit("anon_1", "premium_monthly").status shouldBe HttpStatusCode.Conflict
                repository.purchasesOf("anon_1") shouldBe emptyList()
            }
        }

        "the account id the app builds with the shared function is accepted" {
            withBackend { google, repository ->
                google.subscription = {
                    playSubscription(acknowledged = true, obfuscatedAccountId = playObfuscatedAccountId("\$anon_6f1c"))
                }
                submit("\$anon_6f1c", "premium_monthly").status shouldBe HttpStatusCode.OK
                repository.purchasesOf("\$anon_6f1c").size shouldBe 1
            }
        }
    })
