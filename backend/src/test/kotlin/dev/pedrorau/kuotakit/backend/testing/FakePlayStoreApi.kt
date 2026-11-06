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

package dev.pedrorau.kuotakit.backend.testing

import dev.pedrorau.kuotakit.backend.domain.PlayOfferPhase
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimeItem
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimePurchase
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimeState
import dev.pedrorau.kuotakit.backend.domain.PlayStoreApi
import dev.pedrorau.kuotakit.backend.domain.PlaySubscription
import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionItem
import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionState
import dev.pedrorau.kuotakit.backend.domain.Purchase
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The app user most tests buy as; its account id is what a purchase carries unless a test says otherwise. */
const val DEFAULT_BUYER = "anon_1"

/** What a subscription looks like at [dev.pedrorau.kuotakit.backend.domain.NOW] unless a test says otherwise. */
@Suppress("LongParameterList") // a builder with defaults: the long list is what keeps the tests short
fun playSubscription(
    state: PlaySubscriptionState = PlaySubscriptionState.ACTIVE,
    productId: String = "premium_monthly",
    expiryTime: Instant? = dev.pedrorau.kuotakit.backend.domain.NOW + 10.days,
    autoRenewing: Boolean = true,
    offerPhase: PlayOfferPhase = PlayOfferPhase.BASE_PRICE,
    acknowledged: Boolean = false,
    obfuscatedAccountId: String? = playObfuscatedAccountId(DEFAULT_BUYER),
    startTime: Instant? = dev.pedrorau.kuotakit.backend.domain.NOW - 20.days,
    linkedPurchaseToken: String? = null,
) = PlaySubscription(
    state = state,
    startTime = startTime,
    items = listOf(PlaySubscriptionItem(productId, expiryTime, autoRenewing, offerPhase)),
    acknowledged = acknowledged,
    obfuscatedAccountId = obfuscatedAccountId,
    linkedPurchaseToken = linkedPurchaseToken,
)

/** What a one-time purchase looks like unless a test says otherwise: bought, not refunded, not acknowledged. */
@Suppress("LongParameterList") // a builder with defaults: the long list is what keeps the tests short
fun playOneTime(
    state: PlayOneTimeState = PlayOneTimeState.PURCHASED,
    productId: String = "lifetime_unlock",
    refundableQuantity: Int? = 1,
    acknowledged: Boolean = false,
    obfuscatedAccountId: String? = playObfuscatedAccountId(DEFAULT_BUYER),
    completionTime: Instant? = dev.pedrorau.kuotakit.backend.domain.NOW - 2.days,
) = PlayOneTimePurchase(
    state = state,
    completionTime = completionTime,
    items = listOf(PlayOneTimeItem(productId, refundableQuantity)),
    acknowledged = acknowledged,
    obfuscatedAccountId = obfuscatedAccountId,
)

/** A scripted Google Play. Every call is logged, in order, into [log]. */
class FakePlayStoreApi(
    private val log: MutableList<String> = mutableListOf(),
) : PlayStoreApi {
    var subscription: () -> PlaySubscription = { playSubscription() }
    var oneTime: () -> PlayOneTimePurchase = { playOneTime() }
    var acknowledgeFailure: Exception? = null

    val calls: List<String> get() = log

    fun forgetCalls() = log.clear()

    override suspend fun subscription(purchaseToken: String): PlaySubscription {
        yield() // a real call suspends; so must the fake, or the code after it never runs "resumed"
        log += "get:$purchaseToken"
        return subscription()
    }

    override suspend fun acknowledge(
        productId: String,
        purchaseToken: String,
    ) {
        yield()
        log += "acknowledge:$productId:$purchaseToken"
        acknowledgeFailure?.let { throw it }
    }

    override suspend fun oneTimePurchase(purchaseToken: String): PlayOneTimePurchase {
        yield()
        log += "getOneTime:$purchaseToken"
        return oneTime()
    }

    override suspend fun acknowledgeOneTime(
        productId: String,
        purchaseToken: String,
    ) {
        yield()
        log += "acknowledgeOneTime:$productId:$purchaseToken"
        acknowledgeFailure?.let { throw it }
    }
}

/** Wraps a repository and notes every write in the same log as [FakePlayStoreApi], to check the order of things. */
class RecordingPurchaseRepository(
    private val delegate: PurchaseRepository,
    private val log: MutableList<String>,
) : PurchaseRepository by delegate {
    override suspend fun save(
        appUserId: String,
        purchase: Purchase,
    ) {
        log += "save:$appUserId:${purchase.token}"
        delegate.save(appUserId, purchase)
    }
}
