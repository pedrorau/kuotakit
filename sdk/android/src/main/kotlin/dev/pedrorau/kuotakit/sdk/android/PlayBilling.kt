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
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.sdk.BillingAdapter
import dev.pedrorau.kuotakit.sdk.BillingResult
import dev.pedrorau.kuotakit.sdk.PurchaseRequest

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
    private val client: PlayPurchaseClient,
) : BillingAdapter {
    public constructor(context: Context, activity: () -> Activity?) :
        this(context.applicationContext.packageName, BillingClientPlayClient(context.applicationContext, activity))

    override val store: Store = Store.PLAY_STORE

    override suspend fun purchase(request: PurchaseRequest): BillingResult =
        when (val result = client.buy(request.productId, request.accountId)) {
            is PlayPurchaseResult.Purchased -> {
                BillingResult.Purchased(PlayStoreReceiptRequest(packageName, request.productId, result.purchaseToken))
            }

            PlayPurchaseResult.Cancelled -> {
                BillingResult.Cancelled
            }

            PlayPurchaseResult.Pending -> {
                BillingResult.Pending
            }
        }
}
