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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val NOT_RENEWING = Samples.CUSTOMER_PREMIUM.replace(""""will_renew":true""", """"will_renew":false""")

private const val INACTIVE =
    """{"app_user_id":"anon","request_date":"2026-11-13T01:40:00Z","entitlements":{"premium":{"is_active":false,""" +
        """"product_id":"premium_monthly","store":"play_store","period_type":"normal",""" +
        """"expires_at":"2026-11-13T01:37:36Z","will_renew":false,"billing_issue_detected_at":null}},""" +
        """"active_subscriptions":[],"non_subscription_purchases":[]}"""

private val PACKAGE = OfferingPackage("monthly", StoreProducts("premium_monthly", null))

/** The store owns the one purchase of the user all the time (Google renews it on its own). */
private class OwningStore(
    private val token: String,
    private val answer: BillingResult = BillingResult.Cancelled,
    private val account: () -> String,
) : BillingAdapter {
    override val store: Store = Store.PLAY_STORE
    override val purchaseUpdates: Flow<OwnedPurchase> = MutableSharedFlow()

    override suspend fun purchase(request: PurchaseRequest): BillingResult = answer

    override suspend fun ownedPurchases(): List<OwnedPurchase> =
        listOf(
            OwnedPurchase(
                PlayStoreReceiptRequest("com.acme.app", "premium_monthly", token),
                OwnedState.PURCHASED,
                account(),
            ),
        )
}

/** A backend that answers every receipt with the next answer of [answers], and the last one from then on. */
private class RenewingBackend(
    val answers: List<String>,
    private val limitAfter: Int = Int.MAX_VALUE,
) {
    val receipts = mutableListOf<String>()

    val engine =
        MockEngine { request ->
            val body =
                if (request.url.encodedPath.endsWith("/receipts")) {
                    val token =
                        Regex(
                            """"purchase_token":"([^"]+)"""",
                        ).find(request.body.toByteArray().decodeToString())!!.groupValues[1]
                    receipts += token
                    if (receipts.size > limitAfter) return@MockEngine tooMany()
                    answers[minOf(receipts.size - 1, answers.lastIndex)]
                } else {
                    answers[minOf(receipts.size - 1, answers.lastIndex).coerceAtLeast(0)]
                }
            respond(body, HttpStatusCode.OK, Headers.build { append(HttpHeaders.ContentType, "application/json") })
        }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.tooMany() =
    respond(
        Samples.error("rate_limited"),
        HttpStatusCode.TooManyRequests,
        Headers.build { append(HttpHeaders.ContentType, "application/json") },
    )

class RenewalTest {
    private fun kit(
        billing: BillingAdapter,
        backend: RenewingBackend,
    ) = KuotaKit.create(KuotaKitConfig("https://b.example.com", "pk_abc", billing, MemoryStore()), backend.engine)

    @Test
    fun anExplicitRestoreSendsTheSamePurchaseEveryTimeEvenWhenTheBackendSaysItIsActive() =
        runTest {
            lateinit var kit: KuotaKit
            val store = OwningStore("renewed") { playObfuscatedAccountId(kit.appUserId) }
            val backend = RenewingBackend(listOf(Samples.CUSTOMER_PREMIUM))
            kit = kit(store, backend)

            kit.restore()
            kit.restore()
            kit.restore()
            assertEquals(listOf("renewed", "renewed", "renewed"), backend.receipts)
        }

    @Test
    fun aCancelAfterThePurchaseShowsAtTheNextRestoreWithoutRestartingTheApp() =
        runTest {
            lateinit var kit: KuotaKit
            val store = OwningStore("renewed") { playObfuscatedAccountId(kit.appUserId) }
            val backend = RenewingBackend(listOf(Samples.CUSTOMER_PREMIUM, NOT_RENEWING))
            kit = kit(store, backend)

            kit.restore()
            val second = kit.restore()

            val premium = second.customerInfo.entitlements.getValue("premium")
            assertEquals(true, premium.isActive)
            assertEquals(false, premium.willRenew)
            assertEquals(
                false,
                kit.customerInfo.value
                    ?.entitlements
                    ?.getValue("premium")
                    ?.willRenew,
            )
        }

    @Test
    fun theLimitOfReceiptsReachesAnExplicitRestoreInsteadOfPretendingItWasChecked() =
        runTest {
            lateinit var kit: KuotaKit
            val store = OwningStore("renewed") { playObfuscatedAccountId(kit.appUserId) }
            // The backend answers the first receipt and then refuses more receipts (429).
            val backend = RenewingBackend(listOf(INACTIVE), limitAfter = 1)
            kit = kit(store, backend)

            kit.restore()
            assertFailsWith<KuotaKitException.RateLimited> { kit.restore() }
        }

    @Test
    fun theSyncOfAPurchaseOfWhatIsAlreadyOwnedDoesNotSendAgainWhatTheBackendSaysIsActive() =
        runTest {
            lateinit var kit: KuotaKit
            val store =
                OwningStore("renewed", answer = BillingResult.AlreadyOwned) { playObfuscatedAccountId(kit.appUserId) }
            val backend = RenewingBackend(listOf(Samples.CUSTOMER_PREMIUM))
            kit = kit(store, backend)

            kit.purchase(PACKAGE)
            kit.purchase(PACKAGE)
            assertEquals(listOf("renewed"), backend.receipts)
        }

    @Test
    fun theSyncOfAPurchaseOfWhatIsAlreadyOwnedAsksAgainWhatTheBackendSaysEndedAndOnceItIsActiveItDoesNot() =
        runTest {
            lateinit var kit: KuotaKit
            val store =
                OwningStore("renewed", answer = BillingResult.AlreadyOwned) { playObfuscatedAccountId(kit.appUserId) }
            // 1st receipt: the backend thinks the plan ended; 2nd: Google renewed it, the backend says active.
            val backend = RenewingBackend(listOf(INACTIVE, Samples.CUSTOMER_PREMIUM))
            kit = kit(store, backend)

            kit.purchase(PACKAGE)
            kit.purchase(PACKAGE)
            kit.purchase(PACKAGE)
            assertEquals(listOf("renewed", "renewed"), backend.receipts)
        }
}
