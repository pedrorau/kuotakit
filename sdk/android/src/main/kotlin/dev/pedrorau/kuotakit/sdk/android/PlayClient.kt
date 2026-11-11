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

import kotlinx.coroutines.flow.Flow

/** A kind of product in Google Play: a subscription or a product bought once. */
internal enum class ProductKind { SUBS, INAPP }

/** A product as Google knows it. [handle] is Google's own object, which only the real client opens. */
internal class PlayProduct(
    val id: String,
    val kind: ProductKind,
    val handle: Any,
)

/** What Google answered to a call: a response code of `BillingResponseCode` and its message. */
internal data class PlayResponse(
    val code: Int,
    val message: String = "",
)

internal sealed interface ProductQuery {
    data class Found(
        val product: PlayProduct,
    ) : ProductQuery

    /** Google answered, and does not know the product (as this kind). */
    data object NotFound : ProductQuery

    data class Failed(
        val response: PlayResponse,
    ) : ProductQuery
}

internal enum class PlayState { PURCHASED, PENDING, OTHER }

/** A purchase of the user in Google Play, without Google's types. */
internal data class PlayOwned(
    val token: String,
    val products: List<String>,
    val state: PlayState,
    val accountId: String?,
)

internal sealed interface OwnedQuery {
    data class Listed(
        val purchases: List<PlayOwned>,
    ) : OwnedQuery

    data class Failed(
        val response: PlayResponse,
    ) : OwnedQuery
}

/** What Google reported through its purchases listener: a response and the purchases it carries. */
internal data class PlayUpdate(
    val response: PlayResponse,
    val purchases: List<PlayOwned>,
)

/**
 * The part of Play Billing the adapter needs, with our own types, so everything the adapter decides can be tested
 * without a device. The real implementation only translates to and from Google's classes. It has no way to
 * acknowledge a purchase on purpose: the backend does that (ADR 0013).
 */
internal interface PlayClient {
    suspend fun connect(): PlayResponse

    suspend fun queryProduct(
        productId: String,
        kind: ProductKind,
    ): ProductQuery

    /** Shows the purchase screen for [product], attaching [accountId] to the purchase (ADR 0017). */
    suspend fun launch(
        product: PlayProduct,
        accountId: String,
    ): PlayResponse

    suspend fun queryOwned(kind: ProductKind): OwnedQuery

    /** Everything Google's purchases listener reports, from the moment someone collects it. */
    val updates: Flow<PlayUpdate>
}
