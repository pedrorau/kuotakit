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

import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.flow.Flow

/**
 * What the SDK asks of a store to buy a product. [accountId] is [dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId]
 * of [appUserId]: the adapter must attach it to the purchase when it starts it (ADR 0017), or the backend refuses it.
 */
public data class PurchaseRequest(
    val productId: String,
    val appUserId: String,
    val accountId: String,
)

/** What a store answered. Whether the user has access is decided by the backend, never from this. */
public sealed interface BillingResult {
    /** The store completed the purchase; [receipt] is what the backend needs to check it. */
    public data class Purchased(
        val receipt: ReceiptRequest,
    ) : BillingResult

    /** The user closed the purchase screen. */
    public data object Cancelled : BillingResult

    /** The payment is not complete yet (a pending purchase): nothing is owed and nothing is granted. */
    public data object Pending : BillingResult

    /** The store says the user already owns the product: the SDK looks up what the user owns and sends it. */
    public data object AlreadyOwned : BillingResult
}

/**
 * The part of the SDK that talks to a store: the billing library of the platform (ADR 0002). It does not
 * acknowledge anything: the backend does, after it checked the purchase (ADR 0013). A failure of the store is a
 * [KuotaKitException].
 */
public interface BillingAdapter {
    /** The store this adapter buys from; it decides which product of an offering package is bought. */
    public val store: Store

    public suspend fun purchase(request: PurchaseRequest): BillingResult

    /**
     * Every purchase the store says the user owns, in any state. A failed query is an exception: it is never an empty
     * list, or a restore would look like "nothing to restore".
     */
    public suspend fun ownedPurchases(): List<OwnedPurchase>

    /**
     * Purchases that arrive while no [purchase] call is waiting: a pending payment that is approved later, or one made
     * outside the app. The SDK sends them to the backend.
     */
    public val purchaseUpdates: Flow<OwnedPurchase>
}

/** A small place on the device to keep strings, such as the anonymous app user id. */
public interface KeyValueStore {
    public fun get(key: String): String?

    public fun put(
        key: String,
        value: String,
    )
}

/** The result of [KuotaKit.purchase]. A failure is a [KuotaKitException], not a value of this type. */
public sealed interface PurchaseOutcome {
    /** The backend checked the purchase; [customerInfo] is its answer, and the only truth about access. */
    public data class Success(
        val customerInfo: dev.pedrorau.kuotakit.contracts.CustomerInfo,
    ) : PurchaseOutcome

    public data object Cancelled : PurchaseOutcome

    public data object Pending : PurchaseOutcome

    /**
     * The store said the user already owns the product. The SDK looked up what the user owns and sent it to the
     * backend; [customerInfo] is what the backend says now.
     */
    public data class AlreadyOwned(
        val customerInfo: dev.pedrorau.kuotakit.contracts.CustomerInfo,
    ) : PurchaseOutcome
}
