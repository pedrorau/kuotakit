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

package dev.pedrorau.kuotakit.sdk.android

import com.android.billingclient.api.BillingClient.BillingResponseCode
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import dev.pedrorau.kuotakit.sdk.BillingResult
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.OwnedPurchase
import dev.pedrorau.kuotakit.sdk.OwnedState
import dev.pedrorau.kuotakit.sdk.PurchaseRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val ACCOUNT = playObfuscatedAccountId("\$anon_abc")
private val REQUEST = PurchaseRequest("premium_monthly", "\$anon_abc", ACCOUNT)

/** A client that knows the product as a subscription and answers the purchase screen with [update]. */
private fun client(update: PlayUpdate? = PlayUpdate(OK, listOf(owned("tok1", account = ACCOUNT)))) =
    FakePlayClient().apply {
        knows("premium_monthly", ProductKind.SUBS)
        afterLaunch = update
    }

class PlayBillingTest {
    @Test
    fun itBuysFromGooglePlay() {
        assertEquals(Store.PLAY_STORE, PlayBilling("com.acme.app", client()).store)
    }

    @Test
    fun theProductAndTheAccountIdOfTheUserGoToGoogle() =
        runTest {
            val client = client()
            PlayBilling("com.acme.app", client).purchase(REQUEST)
            assertEquals(listOf("premium_monthly" to ACCOUNT), client.launched)
            assertEquals(
                64,
                client.launched
                    .single()
                    .second.length,
            )
        }

    @Test
    fun theReceiptNamesThePackageTheProductAndTheToken() =
        runTest {
            val result = PlayBilling("com.acme.app", client()).purchase(REQUEST)
            assertEquals(
                BillingResult.Purchased(PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "tok1")),
                result,
            )
        }

    @Test
    fun aCancelAndAPendingPaymentAreNotPurchases() =
        runTest {
            val cancelled = client(PlayUpdate(PlayResponse(BillingResponseCode.USER_CANCELED), emptyList()))
            assertEquals(BillingResult.Cancelled, PlayBilling("p", cancelled).purchase(REQUEST))
            val pending = client(PlayUpdate(OK, listOf(owned("tok1", state = PlayState.PENDING, account = ACCOUNT))))
            assertEquals(BillingResult.Pending, PlayBilling("p", pending).purchase(REQUEST))
        }

    @Test
    fun aFailureOfBillingReachesTheAppAsItsException() =
        runTest {
            val failing = client(PlayUpdate(PlayResponse(BillingResponseCode.SERVICE_UNAVAILABLE, "down"), emptyList()))
            assertFailsWith<KuotaKitException.StoreUnavailable> { PlayBilling("p", failing).purchase(REQUEST) }
        }

    @Test
    fun aSubscriptionIsLookedUpBeforeAProductBoughtOnce() =
        runTest {
            val client =
                FakePlayClient().apply {
                    knows("lifetime", ProductKind.INAPP)
                    afterLaunch = PlayUpdate(OK, listOf(owned("tok9", "lifetime", account = ACCOUNT)))
                }
            val result =
                PlayBilling(
                    "com.acme.app",
                    client,
                ).purchase(PurchaseRequest("lifetime", "\$anon_abc", ACCOUNT))
            assertEquals(listOf("connect", "query SUBS", "query INAPP", "launch"), client.calls)
            assertEquals(BillingResult.Purchased(PlayStoreReceiptRequest("com.acme.app", "lifetime", "tok9")), result)
        }

    @Test
    fun aProductGoogleDoesNotKnowIsAnInvalidRequestAndNothingIsLaunched() =
        runTest {
            val client = FakePlayClient()
            assertFailsWith<KuotaKitException.InvalidRequest> { PlayBilling("p", client).purchase(REQUEST) }
            assertEquals(emptyList(), client.launched)
        }

    @Test
    fun aFailedQueryIsAnErrorNotAnUnknownProduct() =
        runTest {
            val client = FakePlayClient()
            client.known["premium_monthly" to ProductKind.SUBS] =
                ProductQuery.Failed(PlayResponse(BillingResponseCode.NETWORK_ERROR, "no network"))
            assertFailsWith<KuotaKitException.StoreUnavailable> { PlayBilling("p", client).purchase(REQUEST) }
            assertEquals(listOf("connect", "query SUBS"), client.calls)
        }

    @Test
    fun withoutAConnectionNothingElseIsAsked() =
        runTest {
            val client =
                client().apply {
                    connectAnswer =
                        PlayResponse(BillingResponseCode.BILLING_UNAVAILABLE, "no billing")
                }
            assertFailsWith<KuotaKitException.StoreUnavailable> { PlayBilling("p", client).purchase(REQUEST) }
            assertFailsWith<KuotaKitException.StoreUnavailable> { PlayBilling("p", client).ownedPurchases() }
            assertEquals(listOf("connect", "connect"), client.calls)
        }

    @Test
    fun onlyAPurchasedPurchaseOfTheProductThatWasAskedForIsAReceipt() =
        runTest {
            val others =
                listOf(
                    PlayUpdate(OK, listOf(owned("t", state = PlayState.OTHER, account = ACCOUNT))),
                    PlayUpdate(OK, emptyList()),
                )
            others.forEach { update ->
                assertFailsWith<KuotaKitException.Unexpected> { PlayBilling("p", client(update)).purchase(REQUEST) }
            }
        }

    @Test
    fun alreadyOwnedIsReportedWhetherGoogleSaysItWhenLaunchingOrAfter() =
        runTest {
            val whenLaunching =
                client(
                    null,
                ).apply { launchAnswer = PlayResponse(BillingResponseCode.ITEM_ALREADY_OWNED) }
            assertEquals(BillingResult.AlreadyOwned, PlayBilling("p", whenLaunching).purchase(REQUEST))
            val after = client(PlayUpdate(PlayResponse(BillingResponseCode.ITEM_ALREADY_OWNED), emptyList()))
            assertEquals(BillingResult.AlreadyOwned, PlayBilling("p", after).purchase(REQUEST))
        }

    @Test
    fun aCodeNobodyHandlesIsAnErrorNeverAPurchase() =
        runTest {
            val odd = client(PlayUpdate(PlayResponse(BillingResponseCode.DEVELOPER_ERROR, "bad"), emptyList()))
            assertFailsWith<KuotaKitException.Unexpected> { PlayBilling("p", odd).purchase(REQUEST) }
        }

    @Test
    fun ownedPurchasesCarryTheAccountIdTheStateAndOneReceiptPerProduct() =
        runTest {
            val client = FakePlayClient()
            client.ownedAnswers[ProductKind.SUBS] =
                OwnedQuery.Listed(
                    listOf(owned("a", account = ACCOUNT), owned("b", state = PlayState.PENDING, account = null)),
                )
            client.ownedAnswers[ProductKind.INAPP] =
                OwnedQuery.Listed(listOf(PlayOwned("c", listOf("x", "y"), PlayState.OTHER, "other")))
            val owned = PlayBilling("com.acme.app", client).ownedPurchases()
            assertEquals(
                listOf(
                    OwnedPurchase(
                        PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "a"),
                        OwnedState.PURCHASED,
                        ACCOUNT,
                    ),
                    OwnedPurchase(
                        PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "b"),
                        OwnedState.PENDING,
                        null,
                    ),
                    OwnedPurchase(PlayStoreReceiptRequest("com.acme.app", "x", "c"), OwnedState.UNKNOWN, "other"),
                    OwnedPurchase(PlayStoreReceiptRequest("com.acme.app", "y", "c"), OwnedState.UNKNOWN, "other"),
                ),
                owned,
            )
            assertEquals(listOf("connect", "owned SUBS", "owned INAPP"), client.calls)
        }

    @Test
    fun aFailedQueryOfOwnedPurchasesIsAnErrorNeverAnEmptyList() =
        runTest {
            listOf(ProductKind.SUBS, ProductKind.INAPP).forEach { failing ->
                val client = FakePlayClient()
                client.ownedAnswers[failing] =
                    OwnedQuery.Failed(PlayResponse(BillingResponseCode.SERVICE_UNAVAILABLE, "down"))
                assertFailsWith<KuotaKitException.StoreUnavailable> { PlayBilling("p", client).ownedPurchases() }
            }
        }

    @Test
    fun anUpdateWithNoPurchaseWaitingIsForwardedToTheSdk() =
        runTest {
            val client = FakePlayClient()
            val billing = PlayBilling("com.acme.app", client)
            val forwarded = mutableListOf<OwnedPurchase>()
            backgroundScope.launch { billing.purchaseUpdates.toList(forwarded) }
            runCurrent()

            client.reported.emit(PlayUpdate(OK, listOf(owned("late", account = ACCOUNT))))
            client.reported.emit(
                PlayUpdate(PlayResponse(BillingResponseCode.ERROR), listOf(owned("failed", account = ACCOUNT))),
            )
            runCurrent()

            assertEquals(
                listOf(
                    OwnedPurchase(
                        PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "late"),
                        OwnedState.PURCHASED,
                        ACCOUNT,
                    ),
                ),
                forwarded,
            )
        }

    @Test
    fun theUpdateThatAnswersAPurchaseCallIsNotForwardedAgain() =
        runTest {
            val client = client()
            val billing = PlayBilling("com.acme.app", client)
            val forwarded = mutableListOf<OwnedPurchase>()
            backgroundScope.launch { billing.purchaseUpdates.toList(forwarded) }
            runCurrent()

            billing.purchase(REQUEST)
            runCurrent()
            client.reported.emit(PlayUpdate(OK, listOf(owned("tok1", account = ACCOUNT))))
            runCurrent()

            assertEquals(emptyList(), forwarded)
        }

    @Test
    fun afterAFailedPurchaseCallUpdatesAreForwardedAgain() =
        runTest {
            val client = client(null).apply { launchAnswer = PlayResponse(BillingResponseCode.USER_CANCELED) }
            val billing = PlayBilling("com.acme.app", client)
            val forwarded = mutableListOf<OwnedPurchase>()
            backgroundScope.launch { billing.purchaseUpdates.toList(forwarded) }
            runCurrent()

            assertEquals(BillingResult.Cancelled, billing.purchase(REQUEST))
            client.reported.emit(PlayUpdate(OK, listOf(owned("approved-later", account = ACCOUNT))))
            runCurrent()

            assertEquals(
                listOf("approved-later"),
                forwarded.map {
                    it.receipt.let { r -> (r as PlayStoreReceiptRequest).purchaseToken }
                },
            )
        }

    @Test
    fun anUpdateForAnotherProductWhileBuyingIsNotTheAnswerAndIsForwardedInstead() =
        runTest {
            val client = client()
            client.beforeAnswer =
                listOf(PlayUpdate(OK, listOf(owned("approved-meanwhile", "another_product", account = ACCOUNT))))
            val billing = PlayBilling("com.acme.app", client)
            val forwarded = mutableListOf<OwnedPurchase>()
            backgroundScope.launch { billing.purchaseUpdates.toList(forwarded) }
            runCurrent()

            val result = billing.purchase(REQUEST)
            runCurrent()

            assertEquals(
                BillingResult.Purchased(PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "tok1")),
                result,
            )
            assertEquals(
                listOf("approved-meanwhile"),
                forwarded.map { (it.receipt as PlayStoreReceiptRequest).purchaseToken },
            )
        }
}
