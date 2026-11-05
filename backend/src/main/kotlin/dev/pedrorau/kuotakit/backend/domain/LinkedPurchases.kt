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

import dev.pedrorau.kuotakit.contracts.Store
import kotlin.time.Instant

/**
 * A purchase that replaces another (an upgrade, a new sign-up) names the old token in `linkedPurchaseToken`, and
 * Google asks the backend to stop honouring it so it cannot be used to keep access (docs/adr/0015). The old purchase
 * becomes expired as of now. It is not final: Google itself shows the old token as expired, so a later read agrees.
 */
internal class LinkedPurchases(
    private val purchases: PurchaseRepository,
) {
    /** [newToken] is the purchase that replaces it: a link to itself would end the purchase just saved. */
    suspend fun expire(
        linkedToken: String?,
        newToken: String,
        now: Instant,
    ) {
        val owner = linkedToken?.takeIf { it != newToken }?.let { purchases.ownerOf(Store.PLAY_STORE, it) } ?: return
        val old =
            purchases
                .purchasesOf(owner)
                .firstOrNull {
                    it.store == Store.PLAY_STORE && it.token == linkedToken &&
                        it.kind == PurchaseKind.SUBSCRIPTION
                }
        if (old != null && !old.status.isFinal() && old.grantsAccessAt(now)) {
            purchases.save(owner, old.copy(status = PurchaseStatus.EXPIRED, expiresAt = now, updatedAt = now))
        }
    }
}
