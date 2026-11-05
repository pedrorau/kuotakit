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

import kotlin.time.Instant

/** The state of a subscription as Google Play reports it (`subscriptionState` of `subscriptionsv2`). */
enum class PlaySubscriptionState {
    PENDING,
    ACTIVE,
    PAUSED,
    IN_GRACE_PERIOD,
    ON_HOLD,
    CANCELED,
    EXPIRED,
    PENDING_PURCHASE_CANCELED,

    /** Any value this backend does not know (including `SUBSCRIPTION_STATE_UNSPECIFIED`): never grants access. */
    UNKNOWN,
}

/** The phase of an offer a subscription is in (`offerPhase`): how the trial and introductory periods are told apart. */
enum class PlayOfferPhase { FREE_TRIAL, INTRODUCTORY_PRICE, BASE_PRICE, OTHER }

/** One product of a subscription (a subscription can bundle several line items). */
data class PlaySubscriptionItem(
    val productId: String,
    val expiryTime: Instant?,
    val autoRenewing: Boolean,
    val offerPhase: PlayOfferPhase,
)

/** What Google Play says about a purchase token. */
data class PlaySubscription(
    val state: PlaySubscriptionState,
    val startTime: Instant?,
    val items: List<PlaySubscriptionItem>,
    val acknowledged: Boolean,
    /** The account id the app attached to the purchase (`obfuscatedExternalAccountId`), if it did. */
    val obfuscatedAccountId: String?,
    /** The token of the purchase this one replaced (an upgrade or a new sign-up): that one must stop giving access. */
    val linkedPurchaseToken: String? = null,
)

/** The state of a one-time purchase (`purchaseStateContext.purchaseState` of `productsv2`). */
enum class PlayOneTimeState { PURCHASED, CANCELLED, PENDING, UNKNOWN }

/** One product of a one-time purchase. [refundableQuantity] is what has not been refunded yet, if Google says. */
data class PlayOneTimeItem(
    val productId: String,
    val refundableQuantity: Int?,
)

/** What Google Play says about a one-time purchase token. */
data class PlayOneTimePurchase(
    val state: PlayOneTimeState,
    val completionTime: Instant?,
    val items: List<PlayOneTimeItem>,
    val acknowledged: Boolean,
    val obfuscatedAccountId: String?,
)

/**
 * Google Play as the domain needs it. The adapter in `backend.google` talks to the real API; both methods throw
 * [PurchaseInvalidException] when Google says the token is not valid and [StoreUnavailableException] for any other
 * failure, so the domain never has to know what an HTTP status means.
 */
interface PlayStoreApi {
    suspend fun subscription(purchaseToken: String): PlaySubscription

    /** Confirms the purchase to Google; without it Google refunds the purchase after three days. */
    suspend fun acknowledge(
        productId: String,
        purchaseToken: String,
    )

    /** Reads a one-time purchase (`purchases.productsv2.getproductpurchasev2`). Fails like [subscription]. */
    suspend fun oneTimePurchase(purchaseToken: String): PlayOneTimePurchase

    /** Confirms a one-time purchase (`purchases.products.acknowledge`); without it Google refunds it after 3 days. */
    suspend fun acknowledgeOneTime(
        productId: String,
        purchaseToken: String,
    )
}
