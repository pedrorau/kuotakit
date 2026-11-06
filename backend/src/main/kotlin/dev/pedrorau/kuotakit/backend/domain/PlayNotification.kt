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

/** What a Google Play real-time developer notification asks the backend to do (docs/adr/0014). */
sealed interface PlayNotification {
    /** Something changed in a subscription: ask Google for its state (a notification never carries the full state). */
    data class SubscriptionChanged(
        val token: String,
    ) : PlayNotification

    /** Something changed in a one-time purchase (bought, cancelled, partly refunded): ask Google for its state. */
    data class OneTimeChanged(
        val token: String,
    ) : PlayNotification

    /** Google took the subscription away before its end (`SUBSCRIPTION_REVOKED`). */
    data class SubscriptionRevoked(
        val token: String,
    ) : PlayNotification

    /** Google refunded or voided the purchase. */
    data class PurchaseVoided(
        val token: String,
    ) : PlayNotification

    /** Nothing to do: a test message, a price change... */
    data class Ignored(
        val reason: String,
    ) : PlayNotification
}

/** One push from Pub/Sub, already decoded. [messageId] is Pub/Sub's own id, used to skip a duplicate. */
data class PlayPush(
    val messageId: String,
    val packageName: String,
    val notification: PlayNotification,
)

/** What the backend did with a push, for the log and for the tests; every one of them is answered with a 204. */
enum class NotificationOutcome {
    UPDATED,
    DUPLICATE,
    UNKNOWN_PURCHASE,
    ALREADY_FINAL,
    RECORDED_FOR_LATER,
    STORE_REJECTED,
    IGNORED,
    OTHER_APP,
}
