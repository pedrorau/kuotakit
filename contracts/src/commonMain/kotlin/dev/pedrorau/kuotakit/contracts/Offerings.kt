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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `GET /v1/offerings`: what can be bought, as defined in `entitlements.yaml`. */
@Serializable
public data class OfferingsResponse(
    /** The id of the offering to show by default. */
    val current: String,
    val offerings: List<Offering>,
)

@Serializable
public data class Offering(
    val id: String,
    val packages: List<OfferingPackage>,
)

/** A purchasable option of an offering (for example `monthly`) and the store product behind it in each store. */
@Serializable
public data class OfferingPackage(
    val id: String,
    val products: StoreProducts,
)

/** The product identifier of a package in each store; `null` when the package is not sold in that store. */
@Serializable
public data class StoreProducts(
    @SerialName("play_store") val playStore: String?,
    @SerialName("app_store") val appStore: String?,
)
