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
import kotlin.time.Clock

/**
 * Applies Google Play notifications (docs/adr/0014). One rule drives it: a notification may only take access away
 * without asking Google (a refund, a revocation); anything that could give access is read again from Google first,
 * because the notification itself carries no state. Handling the same notification twice gives the same result.
 */
class PlayNotificationService(
    private val playStore: PlayStoreApi?,
    private val androidPackageName: String?,
    private val purchases: PurchaseRepository,
    private val events: ProcessedEventRepository,
    private val clock: Clock,
) {
    /**
     * Throws [StoreUnavailableException] when Google could not be asked: nothing is remembered then, so Pub/Sub's
     * retry does the work again.
     */
    suspend fun handle(push: PlayPush): NotificationOutcome =
        if (events.seen(Store.PLAY_STORE, push.messageId)) {
            NotificationOutcome.DUPLICATE
        } else {
            process(push).also { events.record(Store.PLAY_STORE, push.messageId, clock.now()) }
        }

    private suspend fun process(push: PlayPush): NotificationOutcome {
        if (push.packageName != androidPackageName) return NotificationOutcome.OTHER_APP
        return when (val notification = push.notification) {
            is PlayNotification.Ignored -> NotificationOutcome.IGNORED
            is PlayNotification.SubscriptionRevoked -> end(notification.token, PurchaseStatus.REVOKED)
            is PlayNotification.PurchaseVoided -> end(notification.token, PurchaseStatus.REFUNDED)
            is PlayNotification.SubscriptionChanged -> refresh(notification.token)
        }
    }

    /** Takes access away. A purchase that already ended for good keeps the reason it first had. */
    private suspend fun end(
        token: String,
        status: PurchaseStatus,
    ): NotificationOutcome {
        val known = find(token)
        return when {
            known == null -> {
                NotificationOutcome.UNKNOWN_PURCHASE
            }

            known.purchase.status.isFinal() -> {
                NotificationOutcome.ALREADY_FINAL
            }

            else -> {
                purchases.save(known.owner, known.purchase.copy(status = status, updatedAt = clock.now()))
                NotificationOutcome.UPDATED
            }
        }
    }

    private suspend fun refresh(token: String): NotificationOutcome {
        val known = find(token)
        return when {
            // The webhook never creates a purchase: it cannot know the user. The receipt endpoint reads fresh state.
            known == null -> NotificationOutcome.UNKNOWN_PURCHASE

            known.purchase.status.isFinal() -> NotificationOutcome.ALREADY_FINAL

            else -> update(known, token)
        }
    }

    private suspend fun update(
        known: Known,
        token: String,
    ): NotificationOutcome {
        val api = playStore ?: throw StoreUnavailableException("Google Play is not configured on this backend")
        val now = clock.now()
        return try {
            val subscription = api.subscription(token)
            val purchase =
                PlaySubscriptionMapper.toPurchase(subscription, known.purchase.productId, token, known.purchase, now)
            purchases.save(known.owner, purchase)
            NotificationOutcome.UPDATED
        } catch (ignored: PurchaseInvalidException) {
            // Google no longer describes it as a purchase we understand. The stored end date still limits access.
            NotificationOutcome.STORE_REJECTED
        }
    }

    private class Known(
        val owner: String,
        val purchase: Purchase,
    )

    private suspend fun find(token: String): Known? =
        purchases.ownerOf(Store.PLAY_STORE, token)?.let { owner ->
            purchases
                .purchasesOf(owner)
                .firstOrNull { it.store == Store.PLAY_STORE && it.token == token }
                ?.let { Known(owner, it) }
        }
}
