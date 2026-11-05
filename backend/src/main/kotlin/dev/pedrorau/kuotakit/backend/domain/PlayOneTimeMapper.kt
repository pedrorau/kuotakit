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
 * Turns what Google says about a one-time purchase into a [Purchase] (docs/adr/0015). Fails closed like the
 * subscriptions: only a completed purchase of the product the receipt names, with something left to refund, gives
 * access. A payment still pending, or a purchase cancelled before anything was stored, is refused.
 */
object PlayOneTimeMapper {
    fun toPurchase(
        purchase: PlayOneTimePurchase,
        productId: String,
        token: String,
        existing: Purchase?,
        now: Instant,
    ): Purchase {
        val item =
            purchase.items.firstOrNull { it.productId == productId }
                ?: throw PurchaseInvalidException("The purchase is not for the product the receipt names")
        return Purchase(
            store = Store.PLAY_STORE,
            productId = productId,
            token = token,
            kind = PurchaseKind.NON_CONSUMABLE,
            status = statusOf(purchase.state, item, existing),
            purchasedAt = purchase.completionTime ?: existing?.purchasedAt ?: now,
            expiresAt = null,
            autoRenewing = false,
            periodType = PeriodType.NORMAL,
            billingIssueDetectedAt = null,
            updatedAt = now,
        )
    }

    /**
     * What is left to refund is the only sign of a refund on a completed purchase, and a one-time purchase never
     * expires, so a missing number must not be read as "nothing was refunded" (JSON often leaves out a zero).
     */
    private fun purchasedStatus(item: PlayOneTimeItem): PurchaseStatus {
        val refundable =
            item.refundableQuantity
                ?: throw PurchaseInvalidException("Google Play did not say whether anything is left to refund")
        // Nothing left to refund means everything was refunded, whole or by parts.
        return if (refundable <= 0) PurchaseStatus.REFUNDED else PurchaseStatus.ACTIVE
    }

    private fun statusOf(
        state: PlayOneTimeState,
        item: PlayOneTimeItem,
        existing: Purchase?,
    ): PurchaseStatus =
        when (state) {
            PlayOneTimeState.PURCHASED -> {
                purchasedStatus(item)
            }

            // A cancellation of something we hold ends it; one we never held is a purchase that does not exist.
            PlayOneTimeState.CANCELLED -> {
                if (existing != null) {
                    PurchaseStatus.REVOKED
                } else {
                    throw PurchaseInvalidException("The purchase was cancelled")
                }
            }

            PlayOneTimeState.PENDING -> {
                throw PurchaseInvalidException("The purchase is not complete yet (payment pending)")
            }

            PlayOneTimeState.UNKNOWN -> {
                throw PurchaseInvalidException("Google Play reported an unknown state")
            }
        }
}
