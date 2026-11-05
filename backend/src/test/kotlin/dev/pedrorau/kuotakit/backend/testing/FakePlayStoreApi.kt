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
import dev.pedrorau.kuotakit.backend.domain.PlayStoreApi
import dev.pedrorau.kuotakit.backend.domain.PlaySubscription
import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionItem
import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionState
import dev.pedrorau.kuotakit.backend.domain.Purchase
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** What a subscription looks like at [dev.pedrorau.kuotakit.backend.domain.NOW] unless a test says otherwise. */
@Suppress("LongParameterList") // a builder with defaults: the long list is what keeps the tests short
fun playSubscription(
    state: PlaySubscriptionState = PlaySubscriptionState.ACTIVE,
    productId: String = "premium_monthly",
    expiryTime: Instant? = dev.pedrorau.kuotakit.backend.domain.NOW + 10.days,
    autoRenewing: Boolean = true,
    offerPhase: PlayOfferPhase = PlayOfferPhase.BASE_PRICE,
    acknowledged: Boolean = false,
    obfuscatedAccountId: String? = null,
    startTime: Instant? = dev.pedrorau.kuotakit.backend.domain.NOW - 20.days,
) = PlaySubscription(
    state = state,
    startTime = startTime,
    items = listOf(PlaySubscriptionItem(productId, expiryTime, autoRenewing, offerPhase)),
    acknowledged = acknowledged,
    obfuscatedAccountId = obfuscatedAccountId,
)

/** A scripted Google Play. Every call is logged, in order, into [log]. */
class FakePlayStoreApi(
    private val log: MutableList<String> = mutableListOf(),
) : PlayStoreApi {
    var subscription: () -> PlaySubscription = { playSubscription() }
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
