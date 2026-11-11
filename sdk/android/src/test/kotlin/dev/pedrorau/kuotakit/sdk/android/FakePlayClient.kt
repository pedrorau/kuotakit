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

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

internal val OK = PlayResponse(BillingResponseCode.OK)

internal fun owned(
    token: String,
    product: String = "premium_monthly",
    state: PlayState = PlayState.PURCHASED,
    account: String? = "account-of-the-user",
) = PlayOwned(token, listOf(product), state, account)

/** A Play Billing that knows the products and purchases the test gives it, and records what it is asked, in order. */
internal class FakePlayClient : PlayClient {
    val calls = mutableListOf<String>()
    val launched = mutableListOf<Pair<String, String>>()
    var connectAnswer = OK
    var launchAnswer = OK

    /** What Google's listener reports once the purchase screen is shown (nothing: the screen stays open). */
    var afterLaunch: PlayUpdate? = null

    /** Updates for other purchases that arrive first, while the screen is open. */
    var beforeAnswer: List<PlayUpdate> = emptyList()

    val known = mutableMapOf<Pair<String, ProductKind>, ProductQuery>()
    val ownedAnswers = mutableMapOf<ProductKind, OwnedQuery>()
    val reported = MutableSharedFlow<PlayUpdate>(extraBufferCapacity = 8)

    override val updates: Flow<PlayUpdate> = reported

    fun knows(
        productId: String,
        kind: ProductKind,
    ) {
        known[productId to kind] = ProductQuery.Found(PlayProduct(productId, kind, "handle of $productId"))
    }

    override suspend fun connect(): PlayResponse {
        calls += "connect"
        return connectAnswer
    }

    override suspend fun queryProduct(
        productId: String,
        kind: ProductKind,
    ): ProductQuery {
        calls += "query $kind"
        return known[productId to kind] ?: ProductQuery.NotFound
    }

    /** The replacement each launch carried (`null` for a first purchase), in the same order as [launched]. */
    val replacements = mutableListOf<PlayReplacement?>()

    override suspend fun launch(
        product: PlayProduct,
        accountId: String,
        replacement: PlayReplacement?,
    ): PlayResponse {
        calls += "launch"
        launched += product.id to accountId
        replacements += replacement
        beforeAnswer.forEach { reported.emit(it) }
        afterLaunch?.let { reported.emit(it) }
        return launchAnswer
    }

    override suspend fun queryOwned(kind: ProductKind): OwnedQuery {
        calls += "owned $kind"
        return ownedAnswers[kind] ?: OwnedQuery.Listed(emptyList())
    }
}
