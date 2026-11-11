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

package dev.pedrorau.kuotakit.sdk

import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.StoreProducts
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MONTHLY = OfferingPackage("monthly", StoreProducts("premium_monthly", "com.acme.premium.monthly"))
private val ONLY_APPLE = OfferingPackage("apple", StoreProducts(null, "com.acme.only.apple"))

/** A store that records what it was asked, and answers what the test says. */
private class FakeBilling(
    var answer: () -> BillingResult = {
        BillingResult.Purchased(PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "tok1"))
    },
    override val store: Store = Store.PLAY_STORE,
    val log: MutableList<String> = mutableListOf(),
) : BillingAdapter {
    val requests = mutableListOf<PurchaseRequest>()

    override suspend fun purchase(request: PurchaseRequest): BillingResult {
        log += "store"
        requests += request
        return answer()
    }
}

/** A backend that records every request, in order, and answers by path. */
private class FakeBackend(
    val log: MutableList<String>,
    var receiptAnswer: Pair<HttpStatusCode, String> = HttpStatusCode.OK to Samples.CUSTOMER_PREMIUM,
    var customerAnswer: Pair<HttpStatusCode, String> = HttpStatusCode.OK to Samples.CUSTOMER_EMPTY,
) {
    val requests = mutableListOf<HttpRequestData>()
    val bodies = mutableListOf<String>()

    val engine =
        MockEngine { request ->
            log += "backend ${request.method.value} ${request.url.encodedPath}"
            requests += request
            bodies += request.body.toByteArray().decodeToString()
            val (status, body) =
                when {
                    request.url.encodedPath.endsWith("/receipts") -> receiptAnswer
                    request.url.encodedPath.endsWith("/offerings") -> HttpStatusCode.OK to Samples.OFFERINGS
                    else -> customerAnswer
                }
            respond(
                body,
                status,
                Headers.build {
                    append(HttpHeaders.ContentType, "application/json")
                    append(HttpHeaders.RetryAfter, "9")
                },
            )
        }
}

private fun sdk(
    billing: FakeBilling,
    backend: FakeBackend,
    key: String = "pk_abc",
) = KuotaKit.create(KuotaKitConfig("https://backend.example.com", key, billing, MemoryStore()), backend.engine)

class KuotaKitTest {
    @Test
    fun aSecretKeyIsRefusedBecauseItMustNeverBeInAnApp() {
        val backend = FakeBackend(mutableListOf())
        assertFailsWith<KuotaKitException.InvalidConfiguration> { sdk(FakeBilling(), backend, "sk_secret") }
        assertFailsWith<KuotaKitException.InvalidConfiguration> { sdk(FakeBilling(), backend, "not_a_key") }
        assertFailsWith<KuotaKitException.InvalidConfiguration> {
            KuotaKit.create(
                KuotaKitConfig("backend.example.com", "pk_abc", FakeBilling(), MemoryStore()),
                backend.engine,
            )
        }
    }

    @Test
    fun sharedIsNotAvailableBeforeConfigure() {
        KuotaKit.reset()
        assertFailsWith<KuotaKitException.NotConfigured> { KuotaKit.shared }
    }

    @Test
    fun theCallsCarryTheKeyAndGoToTheRightPaths() =
        runTest {
            val backend = FakeBackend(mutableListOf())
            val kit = sdk(FakeBilling(), backend)
            kit.getOfferings()
            kit.getCustomerInfo()
            assertEquals(
                listOf(
                    "/v1/offerings",
                    "/v1/subscribers/${kit.appUserId}",
                ),
                backend.requests.map {
                    it.url.encodedPath
                },
            )
            backend.requests.forEach {
                assertEquals("Bearer pk_abc", it.headers[HttpHeaders.Authorization])
                assertEquals(HttpMethod.Get, it.method)
            }
        }

    @Test
    fun theCustomerInfoIsWhatTheBackendSaidAndNothingElse() =
        runTest {
            val kit =
                sdk(
                    FakeBilling(),
                    FakeBackend(
                        mutableListOf(),
                        customerAnswer =
                            HttpStatusCode.OK to Samples.CUSTOMER_PREMIUM,
                    ),
                )
            assertNull(kit.customerInfo.value)
            val info = kit.getCustomerInfo()
            assertEquals(info, kit.customerInfo.value)
            assertEquals(true, info.entitlements.getValue("premium").isActive)
        }

    @Test
    fun aFailureLeavesTheLastCustomerInfoAsItWasAndGrantsNothing() =
        runTest {
            val backend = FakeBackend(mutableListOf(), customerAnswer = HttpStatusCode.OK to Samples.CUSTOMER_EMPTY)
            val kit = sdk(FakeBilling(), backend)
            val before = kit.getCustomerInfo()
            backend.customerAnswer = HttpStatusCode.BadGateway to Samples.error("store_unavailable")
            assertFailsWith<KuotaKitException.StoreUnavailable> { kit.getCustomerInfo() }
            assertEquals(before, kit.customerInfo.value)
        }

    @Test
    fun aPurchaseAsksTheStoreFirstWithTheAccountIdAndThenTheBackend() =
        runTest {
            val log = mutableListOf<String>()
            val billing = FakeBilling(log = log)
            val backend = FakeBackend(log)
            val kit = sdk(billing, backend)

            val outcome = kit.purchase(MONTHLY)

            assertEquals(
                listOf("store", "backend POST /v1/subscribers/${kit.appUserId}/receipts"),
                log,
            )
            val request = billing.requests.single()
            assertEquals("premium_monthly", request.productId)
            assertEquals(kit.appUserId, request.appUserId)
            assertEquals(playObfuscatedAccountId(kit.appUserId), request.accountId)
            assertEquals(kit.customerInfo.value, assertIs<PurchaseOutcome.Success>(outcome).customerInfo)
        }

    @Test
    fun theReceiptSentToTheBackendIsTheOneTheStoreGave() =
        runTest {
            val backend = FakeBackend(mutableListOf())
            sdk(FakeBilling(), backend).purchase(MONTHLY)
            val body = backend.bodies.single()
            assertTrue(""""store":"play_store"""" in body, body)
            assertTrue(""""purchase_token":"tok1"""" in body, body)
            assertEquals(
                "application/json",
                backend.requests
                    .single()
                    .body.contentType
                    ?.toString(),
            )
        }

    @Test
    fun aCancelOrAPendingPaymentNeverCallsTheBackend() =
        runTest {
            listOf(
                BillingResult.Cancelled to PurchaseOutcome.Cancelled,
                BillingResult.Pending to PurchaseOutcome.Pending,
            ).forEach { (result, outcome) ->
                val backend = FakeBackend(mutableListOf())
                val kit = sdk(FakeBilling(answer = { result }), backend)
                assertEquals(outcome, kit.purchase(MONTHLY))
                assertEquals(emptyList(), backend.requests)
                assertNull(kit.customerInfo.value)
            }
        }

    @Test
    fun aPackageNotSoldOnThisStoreIsRefusedWithoutAskingTheStore() =
        runTest {
            val billing = FakeBilling()
            val backend = FakeBackend(mutableListOf())
            assertFailsWith<KuotaKitException.InvalidRequest> { sdk(billing, backend).purchase(ONLY_APPLE) }
            assertEquals(emptyList(), billing.requests)
            assertEquals(emptyList(), backend.requests)
        }

    @Test
    fun aRefusedPurchaseIsTheRightErrorAndNeverSuccess() =
        runTest {
            val expected =
                mapOf(
                    400 to "InvalidRequest",
                    401 to "Unauthorized",
                    402 to "PurchaseInvalid",
                    409 to "TokenAlreadyLinked",
                    429 to "RateLimited",
                    500 to "Unexpected",
                    502 to "StoreUnavailable",
                )
            Samples.ERRORS.forEach { (status, body) ->
                val backend = FakeBackend(mutableListOf(), receiptAnswer = HttpStatusCode.fromValue(status) to body)
                val kit = sdk(FakeBilling(), backend)
                val failure = assertFailsWith<KuotaKitException>("status $status") { kit.purchase(MONTHLY) }
                assertEquals(expected.getValue(status), failure::class.simpleName, "status $status")
                assertNull(kit.customerInfo.value, "status $status")
            }
        }

    @Test
    fun aRefusedPurchaseLeavesTheCustomerInfoAsItWas() =
        runTest {
            val backend = FakeBackend(mutableListOf())
            val kit = sdk(FakeBilling(), backend)
            val before = kit.getCustomerInfo()
            backend.receiptAnswer = HttpStatusCode.PaymentRequired to Samples.error("purchase_invalid")
            assertFailsWith<KuotaKitException.PurchaseInvalid> { kit.purchase(MONTHLY) }
            assertEquals(before, kit.customerInfo.value)
        }

    @Test
    fun anOlderAnswerNeverOverwritesANewerOne() =
        runTest {
            var calls = 0
            val engine =
                MockEngine {
                    val first = ++calls == 1
                    delay(if (first) 100 else 10)
                    respond(
                        if (first) Samples.CUSTOMER_EMPTY else Samples.CUSTOMER_PREMIUM,
                        HttpStatusCode.OK,
                        Headers.build { append(HttpHeaders.ContentType, "application/json") },
                    )
                }
            val kit =
                KuotaKit.create(
                    KuotaKitConfig("https://b.example.com", "pk_abc", FakeBilling(), MemoryStore()),
                    engine,
                )
            val slow = async { kit.getCustomerInfo() }
            runCurrent()
            val fast = async { kit.getCustomerInfo() }
            slow.await()
            fast.await()
            assertEquals(
                true,
                kit.customerInfo.value
                    ?.entitlements
                    ?.getValue("premium")
                    ?.isActive,
            )
        }

    @Test
    fun theAppUserIdNeverReachesTheMessageOrTheCauseOfANetworkError() =
        runTest {
            val engine =
                MockEngine {
                    throw IllegalStateException(
                        "failed: https://b.example.com/v1/subscribers/\$anon_thesecret",
                    )
                }
            val kit =
                KuotaKit.create(
                    KuotaKitConfig("https://b.example.com", "pk_abc", FakeBilling(), MemoryStore()),
                    engine,
                )
            val failure = assertFailsWith<KuotaKitException.Network> { kit.getCustomerInfo() }
            assertTrue("thesecret" !in failure.message.orEmpty(), failure.message)
            assertNull(failure.cause)
        }

    @Test
    fun aSlowBackendIsANetworkError() =
        runTest {
            val engine =
                MockEngine {
                    delay(500)
                    respond(Samples.CUSTOMER_EMPTY, HttpStatusCode.OK)
                }
            val api = ApiClient("https://b.example.com", "pk_abc", engine, requestTimeoutMillis = 50)
            assertFailsWith<KuotaKitException.Network> { api.customerInfo("\$anon_abc") }
        }

    @Test
    fun aBackendThatCannotBeReachedIsANetworkErrorAndGrantsNothing() =
        runTest {
            val engine = MockEngine { throw IllegalStateException("connection refused") }
            val kit =
                KuotaKit.create(
                    KuotaKitConfig("https://backend.example.com", "pk_abc", FakeBilling(), MemoryStore()),
                    engine,
                )
            assertFailsWith<KuotaKitException.Network> { kit.purchase(MONTHLY) }
            assertFailsWith<KuotaKitException.Network> { kit.getOfferings() }
            assertNull(kit.customerInfo.value)
        }

    @Test
    fun aGoodStatusWithABodyThatIsNotTheContractIsUnexpected() =
        runTest {
            val backend = FakeBackend(mutableListOf(), customerAnswer = HttpStatusCode.OK to "{\"hello\":1}")
            assertFailsWith<KuotaKitException.Unexpected> { sdk(FakeBilling(), backend).getCustomerInfo() }
        }

    @Test
    fun theWaitOfARateLimitReachesTheApp() =
        runTest {
            val backend =
                FakeBackend(
                    mutableListOf(),
                    customerAnswer =
                        HttpStatusCode.TooManyRequests to Samples.error("rate_limited"),
                )
            val failure =
                assertFailsWith<KuotaKitException.RateLimited> { sdk(FakeBilling(), backend).getCustomerInfo() }
            assertEquals(9, failure.retryAfterSeconds)
        }

    @Test
    fun theSameIdIsUsedWhileTheStorageKeepsIt() {
        val store = MemoryStore()
        val backend = FakeBackend(mutableListOf())
        val first =
            KuotaKit.create(
                KuotaKitConfig("https://b.example.com", "pk_abc", FakeBilling(), store),
                backend.engine,
            )
        val second =
            KuotaKit.create(
                KuotaKitConfig("https://b.example.com", "pk_abc", FakeBilling(), store),
                backend.engine,
            )
        assertEquals(first.appUserId, second.appUserId)
    }
}
