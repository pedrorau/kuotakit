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

import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import dev.pedrorau.kuotakit.sdk.BillingResult
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.ProductReplacement
import dev.pedrorau.kuotakit.sdk.PurchaseRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import dev.pedrorau.kuotakit.sdk.ReplacementMode as SdkMode

private val ACCOUNT = playObfuscatedAccountId("\$anon_abc")
private val OTHER_ACCOUNT = playObfuscatedAccountId("\$anon_other")

private val DEFERRED_CHANGE =
    PurchaseRequest("premium_annual", "\$anon_abc", ACCOUNT, ProductReplacement("premium_monthly", SdkMode.DEFERRED))

/**
 * The user owns the monthly plan (token `old-token`) and changes to the annual one with `DEFERRED`. For such a change
 * Google reports the new purchase with the product that is replaced (`premium_monthly`) until the old plan ends
 * (a hypothesis from the backend side with the real Google on 2026-11-12, to be confirmed with a device log:
 * docs/plans/2026-11-sdk-android-1c.md).
 */
private fun deferredClient(vararg before: PlayUpdate) =
    FakePlayClient().apply {
        knows("premium_annual", ProductKind.SUBS)
        ownedAnswers[ProductKind.SUBS] =
            OwnedQuery.Listed(listOf(owned("old-token", "premium_monthly", account = ACCOUNT)))
        beforeAnswer = before.toList()
        afterLaunch = PlayUpdate(OK, listOf(owned("new-token", "premium_monthly", account = ACCOUNT)))
    }

class DeferredPlanChangeTest {
    @Test
    fun aDeferredChangeIsAnsweredByTheNewPurchaseEvenThoughGoogleListsTheReplacedProduct() =
        runTest(timeout = 10.seconds) {
            val result = PlayBilling("com.acme.app", deferredClient()).purchase(DEFERRED_CHANGE)

            val purchased = assertIs<BillingResult.Purchased>(result)
            // The receipt names the product asked for, with the token of the new purchase (the backend accepts either).
            assertEquals(PlayStoreReceiptRequest("com.acme.app", "premium_annual", "new-token"), purchased.receipt)
        }

    @Test
    fun theReplacedPurchaseItselfNeverAnswersTheCall() =
        runTest(timeout = 10.seconds) {
            val update = PlayUpdate(OK, listOf(owned("old-token", "premium_monthly", account = ACCOUNT)))
            val result = PlayBilling("com.acme.app", deferredClient(update)).purchase(DEFERRED_CHANGE)

            assertEquals(
                "new-token",
                (assertIs<BillingResult.Purchased>(result)).receipt.let {
                    (it as PlayStoreReceiptRequest).purchaseToken
                },
            )
        }

    @Test
    fun aPurchaseOfAnotherAppUserDoesNotAnswerTheCall() =
        runTest(timeout = 10.seconds) {
            val update = PlayUpdate(OK, listOf(owned("someone-else", "premium_monthly", account = OTHER_ACCOUNT)))
            val result = PlayBilling("com.acme.app", deferredClient(update)).purchase(DEFERRED_CHANGE)

            assertEquals(
                "new-token",
                ((result as BillingResult.Purchased).receipt as PlayStoreReceiptRequest).purchaseToken,
            )
        }

    @Test
    fun theReplacedProductAnswersOnlyACallThatReplacesIt() =
        runTest(timeout = 5.seconds) {
            // A first purchase of the annual plan is not answered by a purchase that lists only the monthly one.
            val client =
                FakePlayClient().apply {
                    knows("premium_annual", ProductKind.SUBS)
                    beforeAnswer =
                        listOf(PlayUpdate(OK, listOf(owned("monthly-token", "premium_monthly", account = ACCOUNT))))
                    afterLaunch = PlayUpdate(OK, listOf(owned("annual-token", "premium_annual", account = ACCOUNT)))
                }
            val result =
                PlayBilling("com.acme.app", client).purchase(PurchaseRequest("premium_annual", "\$anon_abc", ACCOUNT))

            assertEquals(
                "annual-token",
                ((result as BillingResult.Purchased).receipt as PlayStoreReceiptRequest).purchaseToken,
            )
        }

    @Test
    fun theBackgroundForwarderDoesNotSendWhatTheWaitingCallAnswers() =
        runTest(timeout = 10.seconds) {
            val client = deferredClient()
            val billing = PlayBilling("com.acme.app", client)
            val forwarded = mutableListOf<String>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    billing.purchaseUpdates.toList().forEach { forwarded += it.receipt.toString() }
                }
            billing.purchase(DEFERRED_CHANGE)
            job.cancel()

            assertEquals(emptyList(), forwarded)
        }
}

/** What the Billing Library throws is never allowed to escape the SDK as something else (rule of the public API). */
private class ThrowingClient(
    private val inner: FakePlayClient,
    private val boom: Throwable,
) : PlayClient by inner {
    override suspend fun queryProduct(
        productId: String,
        kind: ProductKind,
    ): ProductQuery = throw boom

    override suspend fun queryOwned(kind: ProductKind): OwnedQuery = throw boom
}

class UnexpectedLibraryFailureTest {
    private val request = PurchaseRequest("premium_monthly", "\$anon_abc", ACCOUNT)

    @Test
    fun anExceptionOfTheLibraryBecomesAnErrorOfTheSdkWithNothingSecretInIt() =
        runTest {
            val boom = IllegalStateException("token=secret-token user=\$anon_abc")
            val billing = PlayBilling("com.acme.app", ThrowingClient(FakePlayClient(), boom))

            val onPurchase = assertFailsWith<KuotaKitException.Unexpected> { billing.purchase(request) }
            val onOwned = assertFailsWith<KuotaKitException.Unexpected> { billing.ownedPurchases() }

            listOf(onPurchase, onOwned).forEach {
                assertFalse(it.message.orEmpty().contains("secret"), it.message)
                assertFalse(it.message.orEmpty().contains("anon_abc"), it.message)
            }
        }

    @Test
    fun aCancellationIsNeverSwallowed() =
        runTest {
            val billing =
                PlayBilling("com.acme.app", ThrowingClient(FakePlayClient(), CancellationException("cancelled")))
            assertFailsWith<CancellationException> { billing.purchase(request) }
            assertFailsWith<CancellationException> { billing.ownedPurchases() }
        }
}
