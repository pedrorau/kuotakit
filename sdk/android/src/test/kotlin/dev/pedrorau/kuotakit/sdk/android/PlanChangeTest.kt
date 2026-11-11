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
import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import dev.pedrorau.kuotakit.sdk.BillingResult
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.ProductReplacement
import dev.pedrorau.kuotakit.sdk.PurchaseRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import dev.pedrorau.kuotakit.sdk.ReplacementMode as SdkMode

private val ACCOUNT = playObfuscatedAccountId("\$anon_abc")

private fun request(mode: SdkMode = SdkMode.DEFERRED) =
    PurchaseRequest("premium_annual", "\$anon_abc", ACCOUNT, ProductReplacement("premium_monthly", mode))

/** The user owns [owned] as subscriptions; the screen answers with a completed purchase of the new product. */
private fun client(vararg owned: PlayOwned) =
    FakePlayClient().apply {
        knows("premium_annual", ProductKind.SUBS)
        ownedAnswers[ProductKind.SUBS] = OwnedQuery.Listed(owned.toList())
        afterLaunch = PlayUpdate(OK, listOf(owned("new-token", "premium_annual", account = ACCOUNT)))
    }

class PlanChangeTest {
    @Test
    fun aPlanChangeNamesTheOldPurchaseFoundAmongTheOnesTheUserOwnsAndKeepsTheAccountId() =
        runTest {
            val client =
                client(
                    owned("old-token", "premium_monthly", account = ACCOUNT),
                    owned("other", "premium_weekly", account = ACCOUNT),
                )
            val result = PlayBilling("com.acme.app", client).purchase(request(SdkMode.CHARGE_FULL_PRICE))

            assertEquals(listOf("premium_annual" to ACCOUNT), client.launched)
            val replacement = client.replacements.single()
            assertEquals("old-token", replacement?.oldPurchaseToken)
            assertEquals(SdkMode.CHARGE_FULL_PRICE, replacement?.mode)
            assertEquals(true, result is BillingResult.Purchased)
        }

    @Test
    fun aFirstPurchaseCarriesNoReplacement() =
        runTest {
            val client = client()
            PlayBilling("com.acme.app", client).purchase(PurchaseRequest("premium_annual", "\$anon_abc", ACCOUNT))
            assertNull(client.replacements.single())
            assertEquals(false, client.calls.any { it.startsWith("owned") })
        }

    @Test
    fun nothingIsLaunchedWhenThereIsNoSubscriptionOfThisUserToReplace() =
        runTest {
            val unusable =
                mapOf(
                    "the user owns nothing" to emptyList(),
                    "another product" to listOf(owned("t", "premium_weekly", account = ACCOUNT)),
                    "a purchase of another app user" to
                        listOf(owned("t", "premium_monthly", account = playObfuscatedAccountId("\$anon_other"))),
                    "a purchase made outside the app" to listOf(owned("t", "premium_monthly", account = null)),
                    "a pending purchase" to
                        listOf(owned("t", "premium_monthly", state = PlayState.PENDING, account = ACCOUNT)),
                    "a purchase in an unknown state" to
                        listOf(owned("t", "premium_monthly", state = PlayState.OTHER, account = ACCOUNT)),
                )
            unusable.forEach { (name, owned) ->
                val client = client(*owned.toTypedArray())
                assertFailsWith<KuotaKitException.InvalidRequest>(name) { PlayBilling("p", client).purchase(request()) }
                assertEquals(emptyList(), client.launched, name)
            }
        }

    @Test
    fun twoCompletedPurchasesOfTheProductToReplaceAreAmbiguous() =
        runTest {
            val client =
                client(
                    owned("one", "premium_monthly", account = ACCOUNT),
                    owned("two", "premium_monthly", account = ACCOUNT),
                )
            assertFailsWith<KuotaKitException.InvalidRequest> { PlayBilling("p", client).purchase(request()) }
            assertEquals(emptyList(), client.launched)
        }

    @Test
    fun aProductBoughtOnceCannotReplaceASubscription() =
        runTest {
            val client =
                FakePlayClient().apply {
                    knows("lifetime", ProductKind.INAPP)
                    ownedAnswers[ProductKind.SUBS] =
                        OwnedQuery.Listed(listOf(owned("old", "premium_monthly", account = ACCOUNT)))
                }
            val change =
                PurchaseRequest(
                    "lifetime",
                    "\$anon_abc",
                    ACCOUNT,
                    ProductReplacement("premium_monthly", SdkMode.DEFERRED),
                )
            assertFailsWith<KuotaKitException.InvalidRequest> { PlayBilling("p", client).purchase(change) }
            assertEquals(emptyList(), client.launched)
        }

    @Test
    fun aFailedQueryOfWhatTheUserOwnsIsAnErrorAndNothingIsLaunched() =
        runTest {
            val client = client()
            client.ownedAnswers[ProductKind.SUBS] =
                OwnedQuery.Failed(PlayResponse(BillingResponseCode.SERVICE_UNAVAILABLE, "down"))
            assertFailsWith<KuotaKitException.StoreUnavailable> { PlayBilling("p", client).purchase(request()) }
            assertEquals(emptyList(), client.launched)
        }

    @Test
    fun everyModeOfTheSdkIsGooglesOwnConstant() {
        assertEquals(ReplacementMode.CHARGE_PRORATED_PRICE, replacementModeOf(SdkMode.CHARGE_PRORATED_PRICE))
        assertEquals(ReplacementMode.WITH_TIME_PRORATION, replacementModeOf(SdkMode.WITH_TIME_PRORATION))
        assertEquals(ReplacementMode.CHARGE_FULL_PRICE, replacementModeOf(SdkMode.CHARGE_FULL_PRICE))
        assertEquals(ReplacementMode.WITHOUT_PRORATION, replacementModeOf(SdkMode.WITHOUT_PRORATION))
        assertEquals(ReplacementMode.DEFERRED, replacementModeOf(SdkMode.DEFERRED))
        assertEquals(
            SdkMode.entries.size,
            SdkMode.entries
                .map { replacementModeOf(it) }
                .toSet()
                .size,
        )
    }
}
