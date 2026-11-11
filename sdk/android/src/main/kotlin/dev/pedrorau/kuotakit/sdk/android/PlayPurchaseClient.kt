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

package dev.pedrorau.kuotakit.sdk.android

/** What Google answered to a purchase that was started. */
internal sealed interface PlayPurchaseResult {
    data class Purchased(
        val purchaseToken: String,
    ) : PlayPurchaseResult

    data object Cancelled : PlayPurchaseResult

    /** The payment is not complete yet (for example a cash payment): nothing is owed and nothing is granted. */
    data object Pending : PlayPurchaseResult
}

/**
 * The small part of Play Billing the adapter needs, so the rest can be tested without a device. A failure is a
 * `KuotaKitException`. It has no way to acknowledge a purchase on purpose: the backend does that (ADR 0013).
 */
internal interface PlayPurchaseClient {
    /** Shows the purchase screen for [productId], attaching [accountId] to the purchase (ADR 0017). */
    suspend fun buy(
        productId: String,
        accountId: String,
    ): PlayPurchaseResult
}
