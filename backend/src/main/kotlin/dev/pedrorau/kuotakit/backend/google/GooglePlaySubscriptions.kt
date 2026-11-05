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

import com.google.api.services.androidpublisher.model.OfferPhase
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseLineItem
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2
import dev.pedrorau.kuotakit.backend.domain.PlayOfferPhase
import dev.pedrorau.kuotakit.backend.domain.PlaySubscription
import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionItem
import dev.pedrorau.kuotakit.backend.domain.PlaySubscriptionState
import kotlin.time.Instant

/** Turns Google's `SubscriptionPurchaseV2` into the domain's own types, so nothing outside sees Google's. */
internal object GooglePlaySubscriptions {
    private const val STATE_PREFIX = "SUBSCRIPTION_STATE_"
    private const val ACKNOWLEDGED = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"

    fun toDomain(subscription: SubscriptionPurchaseV2): PlaySubscription =
        PlaySubscription(
            state = state(subscription.subscriptionState),
            startTime = instant(subscription.startTime),
            items = subscription.lineItems.orEmpty().map(::item),
            acknowledged = subscription.acknowledgementState == ACKNOWLEDGED,
            obfuscatedAccountId = subscription.externalAccountIdentifiers?.obfuscatedExternalAccountId,
            linkedPurchaseToken = subscription.linkedPurchaseToken?.takeIf { it.isNotBlank() },
        )

    private fun item(item: SubscriptionPurchaseLineItem) =
        PlaySubscriptionItem(
            productId = item.productId.orEmpty(),
            expiryTime = instant(item.expiryTime),
            autoRenewing = item.autoRenewingPlan?.autoRenewEnabled == true,
            offerPhase = phase(item.offerPhase),
        )

    // A state this backend does not know is UNKNOWN, which never grants access.
    private fun state(raw: String?): PlaySubscriptionState =
        PlaySubscriptionState.entries.firstOrNull { raw == STATE_PREFIX + it.name } ?: PlaySubscriptionState.UNKNOWN

    private fun phase(phase: OfferPhase?): PlayOfferPhase =
        when {
            phase?.freeTrial != null -> PlayOfferPhase.FREE_TRIAL
            phase?.introductoryPrice != null -> PlayOfferPhase.INTRODUCTORY_PRICE
            phase?.basePrice != null -> PlayOfferPhase.BASE_PRICE
            else -> PlayOfferPhase.OTHER
        }

    // An end date that cannot be read is no end date: the domain refuses an active subscription without one.
    private fun instant(raw: String?): Instant? = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
