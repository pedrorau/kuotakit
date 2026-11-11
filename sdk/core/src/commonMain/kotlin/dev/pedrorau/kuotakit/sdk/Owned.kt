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

package dev.pedrorau.kuotakit.sdk

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.ReceiptRequest

/** The state of a purchase in the store. Only [PURCHASED] is ever sent to the backend. */
public enum class OwnedState {
    PURCHASED,
    PENDING,

    /** Any other state the store reports: never sent. */
    OTHER,
}

/**
 * A purchase the store says the user owns. [accountId] is the one the app attached when it started the purchase, or
 * `null` (a purchase made outside the app has none).
 */
public data class OwnedPurchase(
    val receipt: ReceiptRequest,
    val state: OwnedState,
    val accountId: String?,
)

/**
 * What [KuotaKit.restore] did: [customerInfo] is what the backend says after it; [submitted] purchases were sent and
 * accepted, [refused] were sent and the backend refused them (it does not recognise them as a valid purchase of this
 * user), and [unattributed] were found in the store but not sent because they carry no account id or another one (made
 * outside the app, or under another app user id): they cannot be recovered. A purchase already accepted earlier in this
 * run of the app is not sent again and is not counted again.
 */
public data class RestoreOutcome(
    val customerInfo: CustomerInfo,
    val submitted: Int,
    val refused: Int,
    val unattributed: Int,
)
