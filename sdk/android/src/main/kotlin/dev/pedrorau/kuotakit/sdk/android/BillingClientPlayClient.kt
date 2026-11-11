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
import com.android.billingclient.api.queryProductDetails
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The Play Billing connection: one [BillingClient] (Google recommends a single one), reconnecting by itself. A product
 * is looked up as a subscription first and as a one-time product after, because the app only knows its id; the
 * backend, not the app, decides which kind it is (ADR 0015).
 */
internal class BillingClientPlayClient(
    context: Context,
    private val activity: () -> Activity?,
) : PlayPurchaseClient {
    private var waiting: CompletableDeferred<PlayPurchaseResult>? = null
    private var waitingFor: String? = null

    private val client: BillingClient =
        BillingClient
            .newBuilder(context)
            .setListener {
                result,
                purchases,
                ->
                onPurchasesUpdated(result.responseCode, result.debugMessage, purchases)
            }.enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()

    override suspend fun buy(
        productId: String,
        accountId: String,
    ): PlayPurchaseResult {
        val screen =
            activity()
                ?: throw KuotaKitException.InvalidRequest("There is no activity to show the purchase screen from")
        connect()
        val params = flowParams(productId, accountId)
        val answer = CompletableDeferred<PlayPurchaseResult>()
        waiting = answer
        waitingFor = productId
        // Google needs the main thread to show the purchase screen, whatever dispatcher the caller is on.
        val launched = withContext(Dispatchers.Main.immediate) { client.launchBillingFlow(screen, params) }
        if (launched.responseCode != BillingResponseCode.OK) {
            waiting = null
            return resultOrThrow(launched.responseCode, launched.debugMessage)
        }
        return try {
            answer.await()
        } finally {
            waiting = null
            waitingFor = null
        }
    }

    private suspend fun connect() {
        if (client.isReady) return
        val code =
            suspendCancellableCoroutine { continuation ->
                client.startConnection(
                    object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                            if (continuation.isActive) continuation.resume(result.responseCode)
                        }

                        override fun onBillingServiceDisconnected() = Unit
                    },
                )
            }
        if (code != BillingResponseCode.OK) throw failureOf(code, "the connection to Google Play failed")
    }

    private suspend fun flowParams(
        productId: String,
        accountId: String,
    ): BillingFlowParams {
        val details =
            detailsOf(productId, BillingClient.ProductType.SUBS)
                ?: detailsOf(productId, BillingClient.ProductType.INAPP)
        val product =
            details ?: throw KuotaKitException.InvalidRequest("Google Play does not offer the product '$productId'")
        // The offer is the base plan when there is one, else the first the user is eligible for.
        val offerToken =
            product.subscriptionOfferDetails?.let { offers ->
                (
                    offers.firstOrNull { it.offerId == null }
                        ?: offers.firstOrNull()
                )?.offerToken
            }
                ?: product.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        val productParams =
            BillingFlowParams.ProductDetailsParams
                .newBuilder()
                .setProductDetails(product)
                .apply { offerToken?.let { setOfferToken(it) } }
                .build()
        return BillingFlowParams
            .newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(accountId)
            .build()
    }

    /** The product as Google knows it, `null` if Google does not know it; a failed query is an error, not "unknown". */
    private suspend fun detailsOf(
        productId: String,
        type: String,
    ): ProductDetails? {
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
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            throw failureOf(result.billingResult.responseCode, result.billingResult.debugMessage)
        }
        return result.productDetailsList?.firstOrNull()
    }

    private fun onPurchasesUpdated(
        code: Int,
        message: String,
        purchases: List<Purchase>?,
    ) {
        val answer = waiting ?: return
        val outcome =
            runCatching {
                when {
                    code == BillingResponseCode.OK -> purchased(purchases.orEmpty())
                    else -> resultOrThrow(code, message)
                }
            }
        outcome.fold(answer::complete, answer::completeExceptionally)
    }

    // Only a purchase that Google says is PURCHASED, of the product that was asked for, is handed on: anything else
    // is pending or an error, never a token (fail closed).
    private fun purchased(purchases: List<Purchase>): PlayPurchaseResult {
        val purchase =
            purchases.firstOrNull { waitingFor in it.products }
                ?: throw KuotaKitException.Unexpected(
                    "Google Play reported no purchase of the product that was asked for",
                )
        return when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PlayPurchaseResult.Purchased(purchase.purchaseToken)
            Purchase.PurchaseState.PENDING -> PlayPurchaseResult.Pending
            else -> throw KuotaKitException.Unexpected("Google Play reported a purchase in an unknown state")
        }
    }

    private fun resultOrThrow(
        code: Int,
        message: String,
    ): PlayPurchaseResult =
        if (code ==
            BillingResponseCode.USER_CANCELED
        ) {
            PlayPurchaseResult.Cancelled
        } else {
            throw failureOf(code, message)
        }
}
