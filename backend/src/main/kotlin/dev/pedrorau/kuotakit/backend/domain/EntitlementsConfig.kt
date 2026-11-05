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

import dev.pedrorau.kuotakit.contracts.Store

/** The products, per store, that grant an entitlement (`entitlements:` in entitlements.yaml). */
data class EntitlementDefinition(
    val products: Map<Store, Set<String>>,
)

/** The product of a package in each store (`offerings:` in entitlements.yaml). */
data class PackageDefinition(
    val products: Map<Store, String>,
)

data class OfferingDefinition(
    val id: String,
    val packages: Map<String, PackageDefinition>,
)

/** What the developer configured in `entitlements.yaml`. Maps keep the order of the file. */
data class EntitlementsConfig(
    val entitlements: Map<String, EntitlementDefinition>,
    val currentOffering: String,
    val offerings: Map<String, OfferingDefinition>,
    /** The products bought once and kept (`one_time_products:`); every other product is a subscription. */
    val oneTimeProducts: Map<Store, Set<String>> = emptyMap(),
) {
    fun isOneTime(
        store: Store,
        productId: String,
    ): Boolean = productId in oneTimeProducts[store].orEmpty()
}
