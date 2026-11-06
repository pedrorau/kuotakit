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

import dev.pedrorau.kuotakit.backend.config.RateLimitConfig
import dev.pedrorau.kuotakit.backend.domain.PushTokenVerifier
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
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
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

private const val TOKEN = "SECRET-purchase-token-123"

private class Limited(
    val google: FakePlayStoreApi = FakePlayStoreApi(),
)

private fun withLimits(
    config: RateLimitConfig,
    block: suspend ApplicationTestBuilder.(Limited) -> Unit,
) = testApplication {
    val limited = Limited()
    val verifier =
        object : PushTokenVerifier {
            override suspend fun verify(bearerToken: String) = bearerToken == "good-token"
        }
    application {
        module(
            testServices(
                InMemoryPurchaseRepository(),
                limited.google,
                InMemoryProcessedEvents(),
                verifier,
                rateLimit = config,
            ),
        )
    }
    block(limited)
}

private suspend fun ApplicationTestBuilder.receipt(
    user: String,
    forwardedFor: String? = null,
    key: String = APP_KEY,
): HttpResponse =
    client.post("/v1/subscribers/$user/receipts") {
        header(HttpHeaders.Authorization, "Bearer $key")
        forwardedFor?.let { header("X-Forwarded-For", it) }
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(
            kuotaKitJson.encodeToString(
                ReceiptRequest.serializer(),
                PlayStoreReceiptRequest(ANDROID_PACKAGE, "premium_monthly", TOKEN + user),
            ),
        )
    }

private suspend fun ApplicationTestBuilder.offerings(
    forwardedFor: String? = null,
    key: String = APP_KEY,
): HttpResponse =
    client.get("/v1/offerings") {
        header(HttpHeaders.Authorization, "Bearer $key")
        forwardedFor?.let { header("X-Forwarded-For", it) }
    }

private suspend fun HttpResponse.code() = kuotaKitJson.decodeFromString<ErrorResponse>(bodyAsText()).error.code

class RateLimitRouteTest :
    StringSpec({
        "one user cannot make the backend ask Google over and over, and others are not affected" {
            withLimits(RateLimitConfig(receiptsPerUserPerMinute = 2)) { limited ->
                limited.google.subscription = { playSubscription(acknowledged = true) }
                receipt("anon_1").status shouldBe HttpStatusCode.OK
                receipt("anon_1").status shouldBe HttpStatusCode.OK
                val limit = receipt("anon_1")
                limit.status shouldBe HttpStatusCode.TooManyRequests
                limit.code() shouldBe ErrorCode.RATE_LIMITED
                // the bucket refills every minute, so the wait is between 1 and 60 seconds
                (limit.headers[HttpHeaders.RetryAfter]!!.toInt() in 1..60) shouldBe true
                OpenApiContract.assertResponseMatches(HttpMethod.Post, "/v1/subscribers/anon_1/receipts", limit)
                limited.google.calls.size shouldBe 2
                receipt("anon_2").status shouldBe HttpStatusCode.OK
            }
        }

        "one client cannot spread the calls over many users to get past the limit" {
            withLimits(RateLimitConfig(receiptsPerMinute = 2)) { limited ->
                limited.google.subscription = { playSubscription(acknowledged = true) }
                receipt("anon_1").status shouldBe HttpStatusCode.OK
                receipt("anon_2").status shouldBe HttpStatusCode.OK
                receipt("anon_3").status shouldBe HttpStatusCode.TooManyRequests
                limited.google.calls.size shouldBe 2
            }
        }

        "every authenticated call is limited per client, and a flood of wrong keys is limited too" {
            withLimits(RateLimitConfig(perMinute = 3)) {
                offerings().status shouldBe HttpStatusCode.OK
                offerings().status shouldBe HttpStatusCode.OK
                offerings().status shouldBe HttpStatusCode.OK
                val limit = offerings()
                limit.status shouldBe HttpStatusCode.TooManyRequests
                limit.code() shouldBe ErrorCode.RATE_LIMITED
                OpenApiContract.assertResponseMatches(HttpMethod.Get, "/v1/offerings", limit)
                // the wrong keys have a bucket of their own: the first is answered, the flood after it is not
                offerings(key = "pk_wrong_wrong_wrong").status shouldBe HttpStatusCode.Unauthorized
                offerings(key = "pk_wrong_wrong_wrong").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        "wrong keys never use up the budget of the calls that carry a good key, even from the same address" {
            withLimits(RateLimitConfig(perMinute = 3)) {
                repeat(20) { _ -> offerings(key = "pk_wrong_wrong_wrong") }
                offerings().status shouldBe HttpStatusCode.OK
                offerings().status shouldBe HttpStatusCode.OK
                offerings().status shouldBe HttpStatusCode.OK
                offerings().status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        "a call without any key or with another kind of header counts as a wrong key" {
            withLimits(RateLimitConfig(perMinute = 3)) {
                repeat(5) { _ -> client.get("/v1/offerings") }
                repeat(5) { _ -> client.get("/v1/offerings") { header(HttpHeaders.Authorization, "Basic $APP_KEY") } }
                offerings().status shouldBe HttpStatusCode.OK
            }
        }

        "health and the Google webhook are not limited: they cost nothing or carry Google's own proof" {
            withLimits(RateLimitConfig(perMinute = 1, receiptsPerMinute = 1, receiptsPerUserPerMinute = 1)) {
                repeat(5) { _ -> client.get("/health").status shouldBe HttpStatusCode.OK }
                repeat(5) { attempt ->
                    val response =
                        client.post("/v1/webhooks/google") {
                            header(HttpHeaders.Authorization, "Bearer good-token")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                pubSubPush("""{"packageName":"$ANDROID_PACKAGE","testNotification":{}}""", "m$attempt"),
                            )
                        }
                    response.status shouldBe HttpStatusCode.NoContent
                }
            }
        }

        "a forwarded address is ignored unless the proxy is trusted, so it cannot be forged to dodge the limit" {
            withLimits(RateLimitConfig(perMinute = 2, trustForwardedFor = false)) {
                offerings(forwardedFor = "1.1.1.1").status shouldBe HttpStatusCode.OK
                offerings(forwardedFor = "2.2.2.2").status shouldBe HttpStatusCode.OK
                offerings(forwardedFor = "3.3.3.3").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        "behind a trusted proxy each client has its own budget, counted by the address the proxy added" {
            withLimits(RateLimitConfig(perMinute = 1, trustForwardedFor = true)) {
                offerings(forwardedFor = "forged-1, 203.0.113.1").status shouldBe HttpStatusCode.OK
                // same client behind the proxy, however it forges the first value: the same budget
                offerings(forwardedFor = "forged-2, 203.0.113.1").status shouldBe HttpStatusCode.TooManyRequests
                // another client: its own budget
                offerings(forwardedFor = "forged-1, 203.0.113.2").status shouldBe HttpStatusCode.OK
            }
        }

        "every id that is not valid shares one budget, so garbage ids cannot make endless buckets" {
            withLimits(RateLimitConfig(receiptsPerUserPerMinute = 2)) { limited ->
                limited.google.subscription = { playSubscription(acknowledged = true) }
                receipt("bad id one").status shouldBe HttpStatusCode.BadRequest
                receipt("bad.id%20two!").status shouldBe HttpStatusCode.BadRequest
                receipt("x".repeat(5000)).status shouldBe HttpStatusCode.TooManyRequests
                // a valid id has its own budget, untouched by the garbage
                receipt("anon_1").status shouldBe HttpStatusCode.OK
            }
        }

        "a client cannot get a new budget by adding a header line of its own when the proxy is trusted" {
            withLimits(RateLimitConfig(perMinute = 1, trustForwardedFor = true)) {
                suspend fun call(vararg lines: String) =
                    client.get("/v1/offerings") {
                        header(HttpHeaders.Authorization, "Bearer $APP_KEY")
                        lines.forEach { header("X-Forwarded-For", it) }
                    }
                call("forged-1", "203.0.113.1").status shouldBe HttpStatusCode.OK
                call("forged-2", "203.0.113.1").status shouldBe HttpStatusCode.TooManyRequests
                call("203.0.113.9", "203.0.113.1").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        "a header that is not an address cannot be used as a key" {
            withLimits(RateLimitConfig(perMinute = 1, trustForwardedFor = true)) {
                offerings(forwardedFor = "garbage-1").status shouldBe HttpStatusCode.OK
                // both fall back to the connection, so they share one budget
                offerings(forwardedFor = "garbage-2").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        "an answer to a limited call never contains the purchase token" {
            withLimits(RateLimitConfig(receiptsPerUserPerMinute = 1)) {
                receipt("anon_1")
                receipt("anon_1").bodyAsText() shouldNotContain TOKEN
            }
        }
    })
