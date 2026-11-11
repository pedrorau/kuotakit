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
    UNKNOWN,
}

/**
 * A purchase the store says the user owns. [accountId] is the one the app attached when it started the purchase, or
 * `null` (a purchase made outside the app has none).
 */
public class OwnedPurchase(
    public val receipt: ReceiptRequest,
    public val state: OwnedState,
    public val accountId: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is OwnedPurchase && receipt == other.receipt && state == other.state && accountId == other.accountId

    override fun hashCode(): Int = listOf(receipt, state, accountId).hashCode()

    // The receipt holds the purchase token and the account id is a hash of a secret of the user: both are left out of
    // what a log can show.
    override fun toString(): String = "OwnedPurchase(state=$state)"
}

/**
 * What [KuotaKit.restore] did: [customerInfo] is what the backend says after it; [submitted] purchases were sent and
 * accepted, [refused] were sent and the backend refused them (it does not recognise them as a valid purchase of this
 * user), and [unattributed] were found in the store but not sent because they carry no account id or another one (made
 * outside the app, or under another app user id): they cannot be recovered. A purchase already accepted earlier in this
 * run of the app is not sent again and is not counted again.
 */
public class RestoreOutcome(
    public val customerInfo: CustomerInfo,
    public val submitted: Int,
    public val refused: Int,
    public val unattributed: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is RestoreOutcome &&
            customerInfo == other.customerInfo &&
            submitted == other.submitted &&
            refused == other.refused &&
            unattributed == other.unattributed

    override fun hashCode(): Int = listOf(customerInfo, submitted, refused, unattributed).hashCode()

    override fun toString(): String =
        "RestoreOutcome(submitted=$submitted, refused=$refused, unattributed=$unattributed)"
}
