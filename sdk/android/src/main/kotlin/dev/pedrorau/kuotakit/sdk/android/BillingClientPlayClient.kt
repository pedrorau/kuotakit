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
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The only class that touches Google's Play Billing classes: it translates to and from [PlayClient] and decides
 * nothing. One [BillingClient] (Google recommends a single one), reconnecting by itself. It cannot be unit tested
 * (Google's classes need a device), so it is kept thin and checked on a device with the example app.
 */
internal class BillingClientPlayClient(
    context: Context,
    private val activity: () -> Activity?,
) : PlayClient {
    private val reported =
        MutableSharedFlow<PlayUpdate>(
            extraBufferCapacity = UPDATES_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private val client: BillingClient =
        BillingClient
            .newBuilder(context)
            .setListener { result, purchases ->
                reported.tryEmit(
                    PlayUpdate(
                        PlayResponse(result.responseCode, result.debugMessage),
                        purchases.orEmpty().map(::playOwned),
                    ),
                )
            }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

    override val updates: Flow<PlayUpdate> = reported.asSharedFlow()

    private val connecting = Mutex()

    // One connection attempt at a time: a sync, a purchase and a restore may all need it at once.
    override suspend fun connect(): PlayResponse =
        connecting.withLock {
            if (client.isReady) PlayResponse(BillingResponseCode.OK) else startConnection()
        }

    private suspend fun startConnection(): PlayResponse =
        suspendCancellableCoroutine { continuation ->
            client.startConnection(
                object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                        if (continuation.isActive) {
                            continuation.resume(
                                PlayResponse(result.responseCode, result.debugMessage),
                            )
                        }
                    }

                    override fun onBillingServiceDisconnected() = Unit
                },
            )
        }

    override suspend fun queryProduct(
        productId: String,
        kind: ProductKind,
    ): ProductQuery {
        val type = if (kind == ProductKind.SUBS) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP
        val product =
            QueryProductDetailsParams.Product
                .newBuilder()
                .setProductId(
                    productId,
                ).setProductType(type)
                .build()
        val result =
            client.queryProductDetails(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
            )
        val response = PlayResponse(result.billingResult.responseCode, result.billingResult.debugMessage)
        val details = result.productDetailsList?.firstOrNull()
        return when {
            response.code != BillingResponseCode.OK -> ProductQuery.Failed(response)
            details == null -> ProductQuery.NotFound
            else -> ProductQuery.Found(PlayProduct(productId, kind, details))
        }
    }

    override suspend fun launch(
        product: PlayProduct,
        accountId: String,
        replacement: PlayReplacement?,
    ): PlayResponse {
        val screen =
            activity()
                ?: throw KuotaKitException.InvalidRequest("There is no activity to show the purchase screen from")
        val details = product.handle as ProductDetails
        // The offer is the base plan when there is one, else the first the user is eligible for.
        val offerToken =
            details.subscriptionOfferDetails?.let { offers ->
                (
                    offers.firstOrNull { it.offerId == null }
                        ?: offers.firstOrNull()
                )?.offerToken
            }
                ?: details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        if (product.kind == ProductKind.SUBS && offerToken == null) {
            throw KuotaKitException.InvalidRequest("Google Play has no offer of '${product.id}' that this user can buy")
        }
        val productParams =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(details)
                .apply { offerToken?.let { setOfferToken(it) } }
                .build()
        val params =
            BillingFlowParams
                .newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .setObfuscatedAccountId(accountId)
                .apply {
                    replacement?.let {
                        setSubscriptionUpdateParams(
                            BillingFlowParams.SubscriptionUpdateParams
                                .newBuilder()
                                .setOldPurchaseToken(it.oldPurchaseToken)
                                .setSubscriptionReplacementMode(replacementModeOf(it.mode))
                                .build(),
                        )
                    }
                }.build()
        // Google needs the main thread to show the purchase screen, whatever dispatcher the caller is on.
        val launched = withContext(Dispatchers.Main.immediate) { client.launchBillingFlow(screen, params) }
        return PlayResponse(launched.responseCode, launched.debugMessage)
    }

    override suspend fun queryOwned(kind: ProductKind): OwnedQuery {
        val type = if (kind == ProductKind.SUBS) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build())
        val response = PlayResponse(result.billingResult.responseCode, result.billingResult.debugMessage)
        return if (response.code ==
            BillingResponseCode.OK
        ) {
            OwnedQuery.Listed(result.purchasesList.map(::playOwned))
        } else {
            OwnedQuery.Failed(response)
        }
    }

    private fun playOwned(purchase: Purchase) =
        PlayOwned(
            token = purchase.purchaseToken,
            products = purchase.products,
            state =
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> PlayState.PURCHASED
                    Purchase.PurchaseState.PENDING -> PlayState.PENDING
                    else -> PlayState.OTHER
                },
            accountId = purchase.accountIdentifiers?.obfuscatedAccountId,
        )

    private companion object {
        const val UPDATES_BUFFER = 16
    }
}
