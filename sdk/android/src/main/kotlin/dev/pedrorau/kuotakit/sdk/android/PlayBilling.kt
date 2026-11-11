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

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient.BillingResponseCode
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.sdk.BillingAdapter
import dev.pedrorau.kuotakit.sdk.BillingResult
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.OwnedPurchase
import dev.pedrorau.kuotakit.sdk.OwnedState
import dev.pedrorau.kuotakit.sdk.ProductReplacement
import dev.pedrorau.kuotakit.sdk.PurchaseRequest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Buys with Google Play Billing (ADR 0002). It attaches the account id to every purchase, as the backend requires
 * (ADR 0017), and hands the purchase token to the core; it never acknowledges the purchase, the backend does.
 *
 * The `activity` of the public constructor gives the activity in the foreground that the purchase screen is shown
 * from, or `null` if there is none. The lambda lives as long as the SDK: it must not hold the activity itself, or it
 * leaks it; keep a weak reference.
 */
public class PlayBilling internal constructor(
    private val packageName: String,
    private val client: PlayClient,
) : BillingAdapter {
    public constructor(context: Context, activity: () -> Activity?) :
        this(context.applicationContext.packageName, BillingClientPlayClient(context.applicationContext, activity))

    override val store: Store = Store.PLAY_STORE

    // While a purchase call is waiting it takes the update that answers it; the others go to `purchaseUpdates`.
    private val waitingFor = AtomicReference<String?>(null)

    // A map used as a set: `newKeySet` needs API 24 and the SDK supports 23.
    private val answered = ConcurrentHashMap<String, Unit>()

    override suspend fun purchase(request: PurchaseRequest): BillingResult {
        connect()
        val product = lookup(request.productId)
        val replacement = request.replacing?.let { replacementFor(it, product, request.accountId) }
        waitingFor.set(request.productId)
        try {
            val update =
                coroutineScope {
                    // Listening starts before the screen is shown, so the answer cannot be missed.
                    val answer =
                        async(
                            start = CoroutineStart.UNDISPATCHED,
                        ) { client.updates.first { answers(it, request.productId) } }
                    val launched = client.launch(product, request.accountId, replacement)
                    if (launched.code == BillingResponseCode.OK) {
                        answer.await()
                    } else {
                        answer.cancel()
                        PlayUpdate(launched, emptyList())
                    }
                }
            return resultOf(update, request.productId)
        } finally {
            waitingFor.set(null)
        }
    }

    /**
     * The subscription a plan change replaces is found among what the user owns, never named by the app (a purchase
     * token is a secret of the user): a completed purchase of that product, made through this app user. Without it
     * nothing is launched.
     */
    private suspend fun replacementFor(
        replacing: ProductReplacement,
        product: PlayProduct,
        accountId: String,
    ): PlayReplacement {
        if (product.kind != ProductKind.SUBS) {
            throw KuotaKitException.InvalidRequest("Only a subscription can replace another one")
        }
        val matching =
            ownedSubscriptions().filter {
                replacing.productId in it.products && it.state == PlayState.PURCHASED && it.accountId == accountId
            }
        // Two completed purchases of the same product by the same user: which one to replace is not guessed.
        val old = matching.singleOrNull() ?: throw cannotReplace(replacing.productId, matching.size)
        return PlayReplacement(old.token, replacing.mode)
    }

    private fun cannotReplace(
        productId: String,
        found: Int,
    ) = KuotaKitException.InvalidRequest(
        if (found ==
            0
        ) {
            "The user has no subscription of '$productId' to replace"
        } else {
            "The user has more than one subscription of '$productId'"
        },
    )

    private suspend fun ownedSubscriptions(): List<PlayOwned> =
        when (val query = client.queryOwned(ProductKind.SUBS)) {
            is OwnedQuery.Listed -> query.purchases
            is OwnedQuery.Failed -> throw failureOf(query.response.code, query.response.message)
        }

    override suspend fun ownedPurchases(): List<OwnedPurchase> {
        connect()
        return listOf(ProductKind.SUBS, ProductKind.INAPP).flatMap { kind ->
            when (val query = client.queryOwned(kind)) {
                is OwnedQuery.Listed -> query.purchases.flatMap(::owned)
                is OwnedQuery.Failed -> throw failureOf(query.response.code, query.response.message)
            }
        }
    }

    // An update answers a waiting purchase call if it is an error, carries no purchase, or carries one of the
    // product asked for; any other (a pending purchase of another product that completes meanwhile) is not.
    private fun answers(
        update: PlayUpdate,
        productId: String,
    ) = update.response.code != BillingResponseCode.OK || update.purchases.isEmpty() ||
        update.purchases.any { productId in it.products }

    override val purchaseUpdates: Flow<OwnedPurchase> =
        client.updates.transform { update ->
            if (update.response.code == BillingResponseCode.OK) {
                val waiting = waitingFor.get()
                update.purchases
                    .filter { !answered.containsKey(it.token) && (waiting == null || waiting !in it.products) }
                    .forEach { purchase -> owned(purchase).forEach { emit(it) } }
            }
        }

    private suspend fun connect() {
        val response = client.connect()
        if (response.code != BillingResponseCode.OK) throw failureOf(response.code, response.message)
    }

    /** The product as a subscription, else as a product bought once: the app only knows its id (ADR 0015). */
    private suspend fun lookup(productId: String): PlayProduct {
        for (kind in ProductKind.entries) {
            when (val query = client.queryProduct(productId, kind)) {
                is ProductQuery.Found -> return query.product
                is ProductQuery.Failed -> throw failureOf(query.response.code, query.response.message)
                ProductQuery.NotFound -> Unit
            }
        }
        throw KuotaKitException.InvalidRequest("Google Play does not offer the product '$productId'")
    }

    // Only a purchase of the product that was asked for, in the state PURCHASED, becomes a receipt: anything else is
    // pending or an error, never a token (fail closed).
    private fun resultOf(
        update: PlayUpdate,
        productId: String,
    ): BillingResult =
        when (update.response.code) {
            BillingResponseCode.OK -> {
                val purchase =
                    update.purchases.firstOrNull { productId in it.products }
                        ?: throw KuotaKitException.Unexpected(
                            "Google Play reported no purchase of the product that was asked for",
                        )
                when (purchase.state) {
                    PlayState.PURCHASED -> {
                        // Kept so the forwarder skips it; a few hundred tokens at most, so it is simply emptied.
                        if (answered.size > MAX_ANSWERED) answered.clear()
                        answered[purchase.token] = Unit
                        BillingResult.Purchased(PlayStoreReceiptRequest(packageName, productId, purchase.token))
                    }

                    PlayState.PENDING -> {
                        BillingResult.Pending
                    }

                    PlayState.OTHER -> {
                        throw KuotaKitException.Unexpected("Google Play reported a purchase in an unknown state")
                    }
                }
            }

            BillingResponseCode.USER_CANCELED -> {
                BillingResult.Cancelled
            }

            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                BillingResult.AlreadyOwned
            }

            else -> {
                throw failureOf(update.response.code, update.response.message)
            }
        }

    private fun owned(purchase: PlayOwned): List<OwnedPurchase> =
        purchase.products.map { product ->
            OwnedPurchase(
                PlayStoreReceiptRequest(packageName, product, purchase.token),
                when (purchase.state) {
                    PlayState.PURCHASED -> OwnedState.PURCHASED
                    PlayState.PENDING -> OwnedState.PENDING
                    PlayState.OTHER -> OwnedState.UNKNOWN
                },
                purchase.accountId,
            )
        }

    private companion object {
        const val MAX_ANSWERED = 256
    }
}
