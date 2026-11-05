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

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.Entitlement
import dev.pedrorau.kuotakit.contracts.NonSubscriptionPurchase
import kotlin.time.Instant

/**
 * Computes what a user may access from their purchases and the configured entitlements. Pure: the same purchases
 * and the same `now` always give the same answer, and it never grants access on doubt (ADR 0003): only a purchase
 * the store reports as active, inside its period, counts.
 */
class EntitlementCalculator(
    private val config: EntitlementsConfig,
) {
    fun customerInfo(
        appUserId: String,
        purchases: List<Purchase>,
        now: Instant,
    ): CustomerInfo =
        CustomerInfo(
            appUserId = appUserId,
            requestDate = now,
            entitlements = entitlements(purchases, now),
            activeSubscriptions = activeSubscriptions(purchases, now),
            nonSubscriptionPurchases = nonSubscriptionPurchases(purchases),
        )

    private fun entitlements(
        purchases: List<Purchase>,
        now: Instant,
    ): Map<String, Entitlement> =
        buildMap {
            config.entitlements.forEach { (id, definition) ->
                val candidates = purchases.filter { it.productId in definition.products[it.store].orEmpty() }
                // An entitlement the user never had is left out; one they had and lost is there, inactive.
                bestOf(candidates, now)?.let { put(id, entitlementOf(it, now)) }
            }
        }

    /** The purchase that decides an entitlement: one that grants access, the one lasting longest; else the latest. */
    private fun bestOf(
        candidates: List<Purchase>,
        now: Instant,
    ): Purchase? {
        val granting = candidates.filter { it.grantsAccessAt(now) }
        return (granting.ifEmpty { candidates }).maxWithOrNull(LONGEST_LASTING)
    }

    private fun entitlementOf(
        purchase: Purchase,
        now: Instant,
    ): Entitlement {
        val active = purchase.grantsAccessAt(now)
        return Entitlement(
            isActive = active,
            productId = purchase.productId,
            store = purchase.store,
            periodType = purchase.periodType,
            expiresAt = purchase.expiresAt,
            willRenew = active && purchase.autoRenewing,
            billingIssueDetectedAt = purchase.billingIssueDetectedAt,
        )
    }

    private fun activeSubscriptions(
        purchases: List<Purchase>,
        now: Instant,
    ): List<String> =
        purchases
            .filter { it.kind == PurchaseKind.SUBSCRIPTION && it.grantsAccessAt(now) }
            .map { it.productId }
            .distinct()
            .sorted()

    private fun nonSubscriptionPurchases(purchases: List<Purchase>): List<NonSubscriptionPurchase> =
        purchases
            .filter { it.kind == PurchaseKind.NON_CONSUMABLE && it.status == PurchaseStatus.ACTIVE }
            .sortedWith(compareBy<Purchase>({ it.purchasedAt }, { it.productId }, { it.token }))
            .map { NonSubscriptionPurchase(it.productId, it.store, it.purchasedAt) }

    private companion object {
        /** A purchase without an end date lasts longest; ties are broken by the latest update, then the token. */
        val LONGEST_LASTING: Comparator<Purchase> =
            compareBy<Purchase>({ it.expiresAt ?: Instant.DISTANT_FUTURE }, { it.updatedAt }, { it.token })
    }
}
