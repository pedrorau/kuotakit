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

package dev.pedrorau.kuotakit.backend.domain

import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import kotlin.time.Instant

/**
 * Turns what Google says about a subscription into a [Purchase], following Google's own rules for who keeps access
 * (docs/adr/0013). It is the most delicate rule of the backend, so it fails closed: only `ACTIVE`, `CANCELED` and
 * `IN_GRACE_PERIOD` inside the paid period grant access, and anything unknown, pending or without an end date
 * is refused instead of guessed.
 */
object PlaySubscriptionMapper {
    fun toPurchase(
        subscription: PlaySubscription,
        productId: String,
        token: String,
        existing: Purchase?,
        now: Instant,
    ): Purchase {
        val item =
            currentItem(subscription.items, productId, now)
                ?: throw PurchaseInvalidException("The purchase is not for the product the receipt names")
        val status = statusOf(subscription.state, item.expiryTime, now)
        return Purchase(
            store = Store.PLAY_STORE,
            productId = item.productId,
            token = token,
            kind = PurchaseKind.SUBSCRIPTION,
            status = status,
            purchasedAt = subscription.startTime ?: existing?.purchasedAt ?: now,
            expiresAt = item.expiryTime,
            autoRenewing = item.autoRenewing,
            periodType = periodTypeOf(item.offerPhase),
            billingIssueDetectedAt = billingIssue(subscription.state, existing, now),
            updatedAt = now,
        )
    }

    /**
     * The item of the purchase the user has now, the only place where this is decided (docs/adr/0020). Usually it is
     * the item of the product asked for. After a plan change with `ReplacementMode.DEFERRED` there are two: the old
     * one, which lasts until its end, and the one that replaces it. The old one is what the user has even if the
     * receipt names the new product, until it ends; then the item that replaces it is, even if a notification still
     * names the old one. An item the user does not own yet (Google leaves out its order) is never current, and so is
     * anything ambiguous (two items that match): both are refused, never guessed.
     */
    private fun currentItem(
        items: List<PlaySubscriptionItem>,
        productId: String,
        now: Instant,
    ): PlaySubscriptionItem? {
        val replaced = items.filter { it.deferredReplacement == productId }
        val named = items.filter { it.productId == productId }
        var item = if (replaced.isEmpty()) named.singleOrNull() else replaced.singleOrNull()
        var steps = 0
        // A chain of replacements is at most as long as the items; the bound only stops a cycle in Google's answer.
        while (item != null && steps++ < items.size) {
            val ended = item.expiryTime?.let { end -> end <= now } == true
            val successor = item.deferredReplacement?.takeIf { ended }
            if (successor == null) break
            item = items.singleOrNull { it.productId == successor }
        }
        return item?.takeIf { it.ownedByUser }
    }

    private fun statusOf(
        state: PlaySubscriptionState,
        expiry: Instant?,
        now: Instant,
    ): PurchaseStatus =
        when (state) {
            PlaySubscriptionState.ACTIVE,
            PlaySubscriptionState.CANCELED,
            PlaySubscriptionState.IN_GRACE_PERIOD,
            -> {
                // Without an end date the purchase would never expire: refuse it instead.
                if (expiry == null) throw PurchaseInvalidException("The subscription has no expiry time")
                if (expiry > now) PurchaseStatus.ACTIVE else PurchaseStatus.EXPIRED
            }

            PlaySubscriptionState.ON_HOLD -> {
                PurchaseStatus.ON_HOLD
            }

            PlaySubscriptionState.PAUSED -> {
                PurchaseStatus.PAUSED
            }

            PlaySubscriptionState.EXPIRED -> {
                PurchaseStatus.EXPIRED
            }

            PlaySubscriptionState.PENDING,
            PlaySubscriptionState.PENDING_PURCHASE_CANCELED,
            -> {
                throw PurchaseInvalidException("The purchase is not complete yet (payment pending)")
            }

            PlaySubscriptionState.UNKNOWN -> {
                throw PurchaseInvalidException("Google Play reported an unknown state")
            }
        }

    private fun periodTypeOf(phase: PlayOfferPhase): PeriodType =
        when (phase) {
            PlayOfferPhase.FREE_TRIAL -> PeriodType.TRIAL
            PlayOfferPhase.INTRODUCTORY_PRICE -> PeriodType.INTRO
            PlayOfferPhase.BASE_PRICE, PlayOfferPhase.OTHER -> PeriodType.NORMAL
        }

    /** A payment problem (grace period or account hold) is noted when first seen and kept while it lasts. */
    private fun billingIssue(
        state: PlaySubscriptionState,
        existing: Purchase?,
        now: Instant,
    ): Instant? =
        when (state) {
            PlaySubscriptionState.IN_GRACE_PERIOD, PlaySubscriptionState.ON_HOLD -> {
                existing?.billingIssueDetectedAt
                    ?: now
            }

            else -> {
                null
            }
        }
}
