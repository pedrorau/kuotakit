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

package dev.pedrorau.kuotakit.backend.google

import com.google.api.services.androidpublisher.model.ProductPurchaseV2
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimeItem
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimePurchase
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimeState
import kotlin.time.Instant

/** Turns Google's `ProductPurchaseV2` into the domain's own types, so nothing outside this package sees Google's. */
internal object GooglePlayOneTime {
    private const val ACKNOWLEDGED = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"

    fun toDomain(purchase: ProductPurchaseV2): PlayOneTimePurchase =
        PlayOneTimePurchase(
            state = state(purchase.purchaseStateContext?.purchaseState),
            completionTime = purchase.purchaseCompletionTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
            items =
                purchase.productLineItem.orEmpty().map {
                    PlayOneTimeItem(it.productId.orEmpty(), it.productOfferDetails?.refundableQuantity)
                },
            acknowledged = purchase.acknowledgementState == ACKNOWLEDGED,
            obfuscatedAccountId = purchase.obfuscatedExternalAccountId,
        )

    // A state this backend does not know is UNKNOWN, which never grants access.
    private fun state(raw: String?): PlayOneTimeState =
        when (raw) {
            "PURCHASED" -> PlayOneTimeState.PURCHASED
            "CANCELLED" -> PlayOneTimeState.CANCELLED
            "PENDING" -> PlayOneTimeState.PENDING
            else -> PlayOneTimeState.UNKNOWN
        }
}
