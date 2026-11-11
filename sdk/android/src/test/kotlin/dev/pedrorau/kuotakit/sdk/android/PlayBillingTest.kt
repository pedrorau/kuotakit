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
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import dev.pedrorau.kuotakit.sdk.BillingResult
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.PurchaseRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class FakePlayClient(
    var answer: () -> PlayPurchaseResult = { PlayPurchaseResult.Purchased("tok1") },
) : PlayPurchaseClient {
    val asked = mutableListOf<Pair<String, String>>()

    override suspend fun buy(
        productId: String,
        accountId: String,
    ): PlayPurchaseResult {
        asked += productId to accountId
        return answer()
    }
}

class PlayBillingTest {
    private val request = PurchaseRequest("premium_monthly", "\$anon_abc", playObfuscatedAccountId("\$anon_abc"))

    @Test
    fun itBuysFromGooglePlay() {
        assertEquals(Store.PLAY_STORE, PlayBilling("com.acme.app", FakePlayClient()).store)
    }

    @Test
    fun theProductAndTheAccountIdOfTheUserGoToGoogle() =
        runTest {
            val client = FakePlayClient()
            PlayBilling("com.acme.app", client).purchase(request)
            assertEquals(listOf("premium_monthly" to playObfuscatedAccountId("\$anon_abc")), client.asked)
            assertEquals(
                64,
                client.asked
                    .single()
                    .second.length,
            )
        }

    @Test
    fun theReceiptNamesThePackageTheProductAndTheToken() =
        runTest {
            val result = PlayBilling("com.acme.app", FakePlayClient()).purchase(request)
            assertEquals(
                BillingResult.Purchased(PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "tok1")),
                result,
            )
        }

    @Test
    fun aCancelAndAPendingPaymentAreNotPurchases() =
        runTest {
            assertEquals(
                BillingResult.Cancelled,
                PlayBilling(
                    "p",
                    FakePlayClient {
                        PlayPurchaseResult.Cancelled
                    },
                ).purchase(request),
            )
            assertEquals(
                BillingResult.Pending,
                PlayBilling("p", FakePlayClient { PlayPurchaseResult.Pending }).purchase(request),
            )
        }

    @Test
    fun aFailureOfBillingReachesTheAppAsItsException() =
        runTest {
            val billing = PlayBilling("p", FakePlayClient { throw KuotaKitException.StoreUnavailable("down") })
            assertFailsWith<KuotaKitException.StoreUnavailable> { billing.purchase(request) }
        }
}
