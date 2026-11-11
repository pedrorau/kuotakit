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

package dev.pedrorau.kuotakit.sdk.e2e

import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.sdk.BillingAdapter
import dev.pedrorau.kuotakit.sdk.BillingResult
import dev.pedrorau.kuotakit.sdk.KeyValueStore
import dev.pedrorau.kuotakit.sdk.OwnedPurchase
import dev.pedrorau.kuotakit.sdk.OwnedState
import dev.pedrorau.kuotakit.sdk.PurchaseRequest
import dev.pedrorau.kuotakit.sdk.ReplacementMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal class MemoryStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }
}

/** What the fake Play does with the next purchase. */
internal enum class Screen { COMPLETE, PENDING, CANCEL }

/**
 * Plays Google Play for the SDK: when the SDK asks it to buy, it tells the fake Google what the real one would report
 * (using the account id the SDK attached), and hands the token back as Play would. What the backend then does with it
 * is the real reference backend.
 */
internal class FakePlay : BillingAdapter {
    override val store = dev.pedrorau.kuotakit.contracts.Store.PLAY_STORE
    override val purchaseUpdates: Flow<OwnedPurchase> = emptyFlow()

    var screen = Screen.COMPLETE

    /** How many times the SDK asked what the user owns, and whether the store hides it for now (see [settle]). */
    val ownedCalls =
        java.util.concurrent.atomic
            .AtomicInteger()

    @Volatile
    var hideOwned = false

    /** What account id Play reports; `null` means "the one the SDK attached", a text replaces it, "" leaves it out. */
    var accountOverride: String? = null

    // The SDK reads it from its own thread (the background sync) while a test writes it.
    val purchases = java.util.concurrent.CopyOnWriteArrayList<Owned>()

    class Owned(
        val product: String,
        val token: String,
        val account: String?,
        @Volatile var state: OwnedState,
    )

    override suspend fun purchase(request: PurchaseRequest): BillingResult {
        if (screen == Screen.CANCEL) return BillingResult.Cancelled
        val account =
            when (accountOverride) {
                null -> request.accountId
                "" -> null
                else -> accountOverride
            }
        val token = freshToken()
        val replaced =
            request.replacing?.let { r ->
                purchases.lastOrNull {
                    it.product == r.productId &&
                        it.state == OwnedState.PURCHASED
                }
            }
        script(request, token, account, replaced)
        val state = if (screen == Screen.PENDING) OwnedState.PENDING else OwnedState.PURCHASED
        purchases += Owned(request.productId, token, account, state)
        return if (screen ==
            Screen.PENDING
        ) {
            BillingResult.Pending
        } else {
            BillingResult.Purchased(PlayStoreReceiptRequest(World.PACKAGE, request.productId, token))
        }
    }

    /** Tells the fake Google what it will say about the purchase. */
    private fun script(
        request: PurchaseRequest,
        token: String,
        account: String?,
        replaced: Owned?,
    ) {
        when {
            request.productId in World.oneTimeProducts -> {
                Google.answer(oneTimePath(token), oneTimeJson(request.productId, account))
            }

            request.replacing?.mode == ReplacementMode.DEFERRED && replaced != null -> {
                Google.answer(
                    subscriptionPath(token),
                    deferredJson(request.replacing!!.productId, request.productId, account.orEmpty(), replaced.token),
                )
            }

            else -> {
                Google.answer(
                    subscriptionPath(token),
                    subscriptionJson(request.productId, account, linked = replaced?.token),
                )
            }
        }
    }

    /** The pending purchase is approved: Google now says it is completed, and so does the store. */
    fun approve() {
        purchases.filter { it.state == OwnedState.PENDING }.forEach { it.state = OwnedState.PURCHASED }
    }

    override suspend fun ownedPurchases(): List<OwnedPurchase> {
        ownedCalls.incrementAndGet()
        if (hideOwned) return emptyList()
        return owned()
    }

    private fun owned(): List<OwnedPurchase> =
        purchases.map {
            OwnedPurchase(PlayStoreReceiptRequest(World.PACKAGE, it.product, it.token), it.state, it.account)
        }
}

/**
 * `KuotaKit.configure` syncs in the background at once, on another thread. A scenario that wants its own `restore()` to
 * be the one that finds a purchase waits here until that first sync has asked the store, so that the two cannot race.
 */
internal fun FakePlay.settle(after: Int) {
    repeat(SETTLE_TRIES) {
        if (ownedCalls.get() > after) return
        Thread.sleep(SETTLE_MILLIS)
    }
    error("the background sync of the SDK did not ask the store in time")
}

private const val SETTLE_TRIES = 200
private const val SETTLE_MILLIS = 25L
