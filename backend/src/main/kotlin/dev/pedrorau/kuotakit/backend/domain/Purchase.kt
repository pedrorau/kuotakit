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

enum class PurchaseKind { SUBSCRIPTION, NON_CONSUMABLE }

/** What the store last said about a purchase. */
enum class PurchaseStatus {
    ACTIVE,
    EXPIRED,
    ON_HOLD,
    PAUSED,
    REFUNDED,
    REVOKED,
    ;

    /** A refund or a revocation is the end of the purchase: nothing the store says afterwards brings it back. */
    fun isFinal(): Boolean = this == REFUNDED || this == REVOKED
}

/**
 * A purchase validated with a store. [token] is the Play purchase token or the App Store original transaction id.
 * A purchase grants access only while the store says it is [PurchaseStatus.ACTIVE] and its period has not ended
 * (fail closed, ADR 0003).
 */
data class Purchase(
    val store: Store,
    val productId: String,
    val token: String,
    val kind: PurchaseKind,
    val status: PurchaseStatus,
    val purchasedAt: Instant,
    /** The end of the current period; `null` for a purchase that never expires (a non-consumable). */
    val expiresAt: Instant?,
    val autoRenewing: Boolean,
    val periodType: PeriodType,
    val billingIssueDetectedAt: Instant?,
    val updatedAt: Instant,
) {
    fun grantsAccessAt(now: Instant): Boolean =
        status == PurchaseStatus.ACTIVE && (expiresAt == null || expiresAt > now)
}
