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

import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.flow.Flow

/**
 * What the SDK asks of a store to buy a product. [accountId] is [dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId]
 * of [appUserId]: the adapter must attach it to the purchase when it starts it (ADR 0017), or the backend refuses it.
 */
public class PurchaseRequest(
    public val productId: String,
    public val appUserId: String,
    public val accountId: String,
    /** The plan this purchase replaces, for a plan change; `null` for a first purchase. */
    public val replacing: ProductReplacement? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PurchaseRequest &&
            productId == other.productId &&
            appUserId == other.appUserId &&
            accountId == other.accountId &&
            replacing == other.replacing

    override fun hashCode(): Int = listOf(productId, appUserId, accountId, replacing).hashCode()

    // The app user id and the account id are secrets of the user: they never reach a log through this.
    override fun toString(): String = "PurchaseRequest(productId=$productId, replacing=$replacing)"
}

/**
 * How the subscription of a plan change is replaced in the store (Google's replacement modes, docs/adr/0019). The
 * modes that take effect at once, and [DEFERRED], which keeps the old plan until it ends.
 */
public enum class ReplacementMode {
    /** An upgrade at once, the billing cycle stays and the price difference for what remains is charged. */
    CHARGE_PRORATED_PRICE,

    /** At once; the remaining time is adjusted by the price difference and credited to the new plan. */
    WITH_TIME_PRORATION,

    /** At once, the user is charged the full price of the new plan and the remaining value is carried over. */
    CHARGE_FULL_PRICE,

    /** At once; the new price is charged when the subscription renews. */
    WITHOUT_PRORATION,

    /** The change takes effect when the old plan ends, which keeps giving access until then (a downgrade). */
    DEFERRED,
}

/** The product that a plan change replaces, and how. */
public class ProductReplacement(
    public val productId: String,
    public val mode: ReplacementMode,
) {
    override fun equals(other: Any?): Boolean =
        other is ProductReplacement && productId == other.productId && mode == other.mode

    override fun hashCode(): Int = listOf(productId, mode).hashCode()

    override fun toString(): String = "ProductReplacement(productId=$productId, mode=$mode)"
}

/** A plan change: buy another package of the offering instead of the one the user has ([replacing]). */
public class PlanChange(
    public val replacing: OfferingPackage,
    public val mode: ReplacementMode,
) {
    override fun equals(other: Any?): Boolean =
        other is PlanChange && replacing == other.replacing && mode == other.mode

    override fun hashCode(): Int = listOf(replacing, mode).hashCode()

    override fun toString(): String = "PlanChange(replacing=${replacing.id}, mode=$mode)"
}

/** What a store answered. Whether the user has access is decided by the backend, never from this. */
public sealed interface BillingResult {
    /** The store completed the purchase; [receipt] is what the backend needs to check it. */
    public class Purchased(
        public val receipt: ReceiptRequest,
    ) : BillingResult {
        override fun equals(other: Any?): Boolean = other is Purchased && receipt == other.receipt

        override fun hashCode(): Int = receipt.hashCode()

        // The receipt holds the purchase token, a secret of the user: it is left out of what a log can show.
        override fun toString(): String = "Purchased()"
    }

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
    public class Success(
        public val customerInfo: dev.pedrorau.kuotakit.contracts.CustomerInfo,
    ) : PurchaseOutcome {
        override fun equals(other: Any?): Boolean = other is Success && customerInfo == other.customerInfo

        override fun hashCode(): Int = customerInfo.hashCode()

        // The customer info holds the app user id, a secret (ADR 0009): only what it grants is shown.
        override fun toString(): String = "Success(entitlements=${customerInfo.entitlements.keys})"
    }

    public data object Cancelled : PurchaseOutcome

    public data object Pending : PurchaseOutcome

    /**
     * The store said the user already owns the product. The SDK looked up what the user owns and sent it to the
     * backend; [customerInfo] is what the backend says now.
     */
    public class AlreadyOwned(
        public val customerInfo: dev.pedrorau.kuotakit.contracts.CustomerInfo,
    ) : PurchaseOutcome {
        override fun equals(other: Any?): Boolean = other is AlreadyOwned && customerInfo == other.customerInfo

        override fun hashCode(): Int = customerInfo.hashCode()

        override fun toString(): String = "AlreadyOwned(entitlements=${customerInfo.entitlements.keys})"
    }
}
