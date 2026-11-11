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

// The mock backend answers on another thread, so these tests wait on the real dispatcher on purpose.
@file:Suppress("InjectDispatcher")

package dev.pedrorau.kuotakit.sdk

import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.StoreProducts
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

private val PACKAGE = OfferingPackage("monthly", StoreProducts("premium_monthly", null))

private fun receipt(token: String) = PlayStoreReceiptRequest("com.acme.app", "premium_monthly", token)

private fun owned(
    token: String,
    account: String?,
    state: OwnedState = OwnedState.PURCHASED,
) = OwnedPurchase(receipt(token), state, account)

/** A store that owns what the test says, and tells when it is asked. */
private class OwningBilling : BillingAdapter {
    var owned: suspend () -> List<OwnedPurchase> = { emptyList() }
    var answer: () -> BillingResult = { BillingResult.AlreadyOwned }

    @kotlin.concurrent.Volatile
    var asked = 0
    var running = 0
    var mostRunning = 0
    val updates = MutableSharedFlow<OwnedPurchase>(extraBufferCapacity = 8)

    override val store: Store = Store.PLAY_STORE
    override val purchaseUpdates: Flow<OwnedPurchase> = updates

    override suspend fun purchase(request: PurchaseRequest): BillingResult = answer()

    override suspend fun ownedPurchases(): List<OwnedPurchase> {
        asked++
        running++
        mostRunning = maxOf(mostRunning, running)
        try {
            return owned()
        } finally {
            running--
        }
    }
}

/** A backend that answers receipts by token, and records what it was sent. */
private class ReceiptBackend {
    val receipts = mutableListOf<String>()
    var requests = 0
    var receiptAnswer: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to Samples.CUSTOMER_PREMIUM }
    var customerAnswer: Pair<HttpStatusCode, String> = HttpStatusCode.OK to Samples.CUSTOMER_EMPTY

    val engine =
        MockEngine { request ->
            requests++
            val (status, body) =
                if (request.url.encodedPath.endsWith("/receipts")) {
                    val token =
                        Regex(
                            """"purchase_token":"([^"]+)"""",
                        ).find(request.body.toByteArray().decodeToString())!!.groupValues[1]
                    receipts += token
                    receiptAnswer(token)
                } else {
                    customerAnswer
                }
            respond(body, status, Headers.build { append(HttpHeaders.ContentType, "application/json") })
        }
}

private fun kit(
    billing: BillingAdapter,
    backend: ReceiptBackend,
) = KuotaKit.create(KuotaKitConfig("https://b.example.com", "pk_abc", billing, MemoryStore()), backend.engine)

private fun TestScope.account(kit: KuotaKit) = playObfuscatedAccountId(kit.appUserId)

class RestoreTest {
    @Test
    fun onlyCompletedPurchasesOfThisUserAreSent() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            val mine = account(kit)
            billing.owned = {
                listOf(
                    owned("good-1", mine),
                    owned("pending", mine, OwnedState.PENDING),
                    owned("other-state", mine, OwnedState.OTHER),
                    owned("no-account", null),
                    owned("another-user", playObfuscatedAccountId("\$anon_someone_else")),
                    owned("good-2", mine),
                )
            }

            val outcome = kit.restore()

            assertEquals(listOf("good-1", "good-2"), backend.receipts)
            assertEquals(2, outcome.submitted)
            assertEquals(0, outcome.refused)
            assertEquals(kit.customerInfo.value, outcome.customerInfo)
        }

    @Test
    fun aRestoreWithNothingToSendStillAnswersWhatTheBackendKnows() =
        runTest {
            val backend = ReceiptBackend()
            backend.customerAnswer = HttpStatusCode.OK to Samples.CUSTOMER_PREMIUM
            val outcome = kit(OwningBilling(), backend).restore()
            assertEquals(emptyList(), backend.receipts)
            assertEquals(0, outcome.submitted)
            assertEquals(
                true,
                outcome.customerInfo.entitlements
                    .getValue("premium")
                    .isActive,
            )
        }

    @Test
    fun aPurchaseTheBackendRefusesDoesNotStopTheOthers() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            val mine = account(kit)
            billing.owned = { listOf("a", "b", "c", "d").map { owned(it, mine) } }
            backend.receiptAnswer = { token ->
                when (token) {
                    "a" -> HttpStatusCode.PaymentRequired to Samples.error("purchase_invalid")
                    "b" -> HttpStatusCode.Conflict to Samples.error("token_already_linked")
                    "c" -> HttpStatusCode.BadRequest to Samples.error("invalid_payload")
                    else -> HttpStatusCode.OK to Samples.CUSTOMER_PREMIUM
                }
            }

            val outcome = kit.restore()

            assertEquals(listOf("a", "b", "c", "d"), backend.receipts)
            assertEquals(1, outcome.submitted)
            assertEquals(3, outcome.refused)
        }

    @Test
    fun aFailureToReachTheBackendStopsTheRestoreAndLeavesEverythingAsItWas() =
        runTest {
            val failures =
                mapOf(
                    HttpStatusCode.BadGateway to Samples.error("store_unavailable"),
                    HttpStatusCode.Unauthorized to Samples.error("unauthorized"),
                    HttpStatusCode.TooManyRequests to Samples.error("rate_limited"),
                    HttpStatusCode.InternalServerError to Samples.error("internal_error"),
                )
            failures.forEach { (status, body) ->
                val billing = OwningBilling()
                val backend = ReceiptBackend()
                val kit = kit(billing, backend)
                billing.owned = { listOf(owned("first", account(kit)), owned("second", account(kit))) }
                backend.receiptAnswer = { status to body }

                assertFailsWith<KuotaKitException>("status $status") { kit.restore() }

                assertEquals(listOf("first"), backend.receipts, "status $status")
                assertNull(kit.customerInfo.value, "status $status")
            }
        }

    @Test
    fun aFailedQueryOfOwnedPurchasesIsAnErrorNeverAnEmptyRestore() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            billing.owned = { throw KuotaKitException.StoreUnavailable("Google Play is not available") }
            assertFailsWith<KuotaKitException.StoreUnavailable> { kit(billing, backend).restore() }
            assertEquals(0, backend.requests)
        }

    @Test
    fun buyingWhatTheUserOwnsRestoresItInsteadOfFailing() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            billing.owned = { listOf(owned("tok-owned", account(kit))) }

            val outcome = kit.purchase(PACKAGE)

            assertEquals(listOf("tok-owned"), backend.receipts)
            assertEquals(kit.customerInfo.value, assertIs<PurchaseOutcome.AlreadyOwned>(outcome).customerInfo)
        }

    @Test
    fun buyingWhatAnotherAppUserOwnsSaysSoInsteadOfPretending() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            billing.owned =
                { listOf(owned("someone-elses", playObfuscatedAccountId("\$anon_other")), owned("outside", null)) }
            val failure = assertFailsWith<KuotaKitException.InvalidRequest> { kit(billing, backend).purchase(PACKAGE) }
            assertEquals(emptyList(), backend.receipts)
            assertEquals(true, "another app user id" in failure.message.orEmpty())
        }

    @Test
    fun aPurchaseTheBackendAlreadyAnsweredIsNotSentAgainButTheCustomerInfoIsReadAgain() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            val mine = account(kit)
            billing.owned =
                {
                    listOf(
                        owned("ok", mine),
                        owned("bad", mine),
                        owned("new", mine).takeIf { billing.asked > 1 },
                    ).filterNotNull()
                }
            backend.receiptAnswer = { token ->
                if (token ==
                    "bad"
                ) {
                    HttpStatusCode.Conflict to Samples.error("token_already_linked")
                } else {
                    HttpStatusCode.OK to
                        Samples.CUSTOMER_PREMIUM
                }
            }

            val first = kit.restore()
            val second = kit.restore()
            val third = kit.restore()

            assertEquals(listOf("ok", "bad", "new"), backend.receipts)
            assertEquals(1, first.submitted)
            assertEquals(1, first.refused)
            assertEquals(0, second.refused)
            assertEquals(1, second.submitted)
            assertEquals(0, third.refused)
        }

    @Test
    fun purchasesOfNoUsOrNobodyAreCountedAsUnattributed() =
        runTest {
            val billing = OwningBilling()
            val kit = kit(billing, ReceiptBackend())
            billing.owned =
                { listOf(owned("promo", null), owned("elsewhere", "someone-else"), owned("mine", account(kit))) }
            val outcome = kit.restore()
            assertEquals(2, outcome.unattributed)
            assertEquals(1, outcome.submitted)
        }

    @Test
    fun buyingWhatTheBackendRefusesSaysItIsRefusedNotThatItIsAnotherUsers() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            billing.owned = { listOf(owned("tok", account(kit))) }
            backend.receiptAnswer = { HttpStatusCode.PaymentRequired to Samples.error("purchase_invalid") }
            assertFailsWith<KuotaKitException.PurchaseInvalid> { kit.purchase(PACKAGE) }
        }

    @Test
    fun buyingAProductWhenTheOwnedOneIsAnotherProductIsNotRestored() =
        runTest {
            val billing = OwningBilling()
            val kit = kit(billing, ReceiptBackend())
            billing.owned =
                {
                    listOf(
                        OwnedPurchase(
                            PlayStoreReceiptRequest("com.acme.app", "another_product", "t"),
                            OwnedState.PURCHASED,
                            account(kit),
                        ),
                    )
                }
            assertFailsWith<KuotaKitException.InvalidRequest> { kit.purchase(PACKAGE) }
        }

    @Test
    fun anUnexpectedErrorInTheBackgroundDoesNotEndTheAppAndTheNextTriggerRecovers() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            var broken = true
            billing.owned = {
                if (broken) error("a bug in the store client")
                listOf(owned("later", account(kit)))
            }

            kit.startBackgroundSync(backgroundScope)
            waitUntil { billing.asked == 1 }
            broken = false
            billing.updates.emit(owned("update", account(kit)))
            waitUntil { backend.receipts.isNotEmpty() }
            assertEquals(listOf("later"), backend.receipts)
        }

    @Test
    fun configuringAgainStopsTheBackgroundSyncOfThePreviousInstance() =
        runTest {
            val billing = OwningBilling()
            val kit = kit(billing, ReceiptBackend())
            kit.startBackgroundSync(backgroundScope)
            waitUntil { billing.asked == 1 }

            kit.stopBackgroundSync()
            billing.updates.emit(owned("update", account(kit)))
            runCurrent()
            withContext(Dispatchers.Default) { delay(200) }

            assertEquals(1, billing.asked)
        }

    @Test
    fun theClassificationDecidesWhatIsSent() {
        val mine = "mine"
        assertEquals(OwnedAction.Send(receipt("t")), classify(owned("t", mine), mine))
        assertEquals(OwnedAction.Waiting, classify(owned("t", mine, OwnedState.PENDING), mine))
        assertEquals(OwnedAction.Ignore, classify(owned("t", mine, OwnedState.OTHER), mine))
        assertEquals(OwnedAction.NotOurs, classify(owned("t", null), mine))
        assertEquals(OwnedAction.NotOurs, classify(owned("t", "theirs"), mine))
        assertEquals(OwnedAction.NotOurs, classify(owned("t", mine.uppercase()), mine))
        assertEquals(OwnedAction.NotOurs, classify(owned("t", "theirs", OwnedState.PENDING), mine))
        assertEquals(OwnedAction.Ignore, classify(owned("t", "theirs", OwnedState.OTHER), mine))
    }

    @Test
    fun theBackgroundSyncSendsWhatIsOwnedAtStartAndWhatArrivesLater() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            billing.owned = { listOf(owned("t-${billing.asked}", account(kit))) }

            kit.startBackgroundSync(backgroundScope)
            waitUntil { backend.receipts.size == 1 }
            assertEquals(listOf("t-1"), backend.receipts)

            billing.updates.emit(owned("update", account(kit)))
            waitUntil { backend.receipts.size == 2 }
            assertEquals(listOf("t-1", "t-2"), backend.receipts)
        }

    @Test
    fun twoSyncsNeverRunAtTheSameTime() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            val gate = CompletableDeferred<Unit>()
            billing.owned = {
                gate.await()
                emptyList()
            }

            kit.startBackgroundSync(backgroundScope)
            waitUntil { billing.asked == 1 }
            billing.updates.emit(owned("u1", account(kit)))
            billing.updates.emit(owned("u2", account(kit)))
            val manual = async { kit.restore() }
            runCurrent()
            gate.complete(Unit)
            withContext(Dispatchers.Default) { manual.await() }
            waitUntil { billing.asked >= 4 }

            assertEquals(1, billing.mostRunning)
        }

    @Test
    fun aFailureInTheBackgroundChangesNothingVisibleAndTheNextTriggerTriesAgain() =
        runTest {
            val billing = OwningBilling()
            val backend = ReceiptBackend()
            val kit = kit(billing, backend)
            var fail = true
            billing.owned = {
                if (fail) throw KuotaKitException.StoreUnavailable("down")
                listOf(owned("later", account(kit)))
            }

            kit.startBackgroundSync(backgroundScope)
            waitUntil { billing.asked == 1 }
            assertNull(kit.customerInfo.value)
            assertEquals(emptyList(), backend.receipts)

            fail = false
            billing.updates.emit(owned("update", account(kit)))
            waitUntil { backend.receipts.isNotEmpty() }
            assertEquals(listOf("later"), backend.receipts)
        }
}

/** The mock backend answers on another thread, so the test waits for real time, not for the virtual clock. */
private suspend fun waitUntil(condition: () -> Boolean) =
    withContext(Dispatchers.Default) {
        repeat(1000) {
            if (condition()) return@withContext
            delay(10)
        }
        error("the condition was not met in ten seconds")
    }
