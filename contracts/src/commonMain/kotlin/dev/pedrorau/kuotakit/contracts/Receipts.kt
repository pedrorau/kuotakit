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

package dev.pedrorau.kuotakit.contracts

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Body of `POST /v1/subscribers/{app_user_id}/receipts`, sent by the SDK after a store purchase. The `store` field
 * says which kind of receipt it is. Encode it as a [ReceiptRequest], not with the serializer of a subtype, or the
 * `store` field is left out (see [kuotaKitJson]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("store")
public sealed interface ReceiptRequest

/** A Google Play purchase: the backend validates the token with the Play Developer API and acknowledges it. */
@Serializable
@SerialName("play_store")
public data class PlayStoreReceiptRequest(
    @SerialName("package_name") val packageName: String,
    @SerialName("product_id") val productId: String,
    @SerialName("purchase_token") val purchaseToken: String,
) : ReceiptRequest

/** An App Store purchase: the JWS signed transaction produced by StoreKit 2. */
@Serializable
@SerialName("app_store")
public data class AppStoreReceiptRequest(
    @SerialName("bundle_id") val bundleId: String,
    @SerialName("signed_transaction") val signedTransaction: String,
) : ReceiptRequest
