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

package dev.pedrorau.kuotakit.openapi

import dev.pedrorau.kuotakit.contracts.AppStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.Entitlement
import dev.pedrorau.kuotakit.contracts.ErrorBody
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.HealthResponse
import dev.pedrorau.kuotakit.contracts.NonSubscriptionPurchase
import dev.pedrorau.kuotakit.contracts.Offering
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.StoreProducts
import kotlinx.serialization.KSerializer

/**
 * Every model of `contracts` that appears in the API, with the name it gets in `components/schemas`. A new model
 * must be added here: the "every @Serializable model is documented" test fails otherwise.
 */
val MODELS: List<Pair<String, KSerializer<*>>> =
    listOf(
        "HealthResponse" to HealthResponse.serializer(),
        "OfferingsResponse" to OfferingsResponse.serializer(),
        "Offering" to Offering.serializer(),
        "OfferingPackage" to OfferingPackage.serializer(),
        "StoreProducts" to StoreProducts.serializer(),
        "ReceiptRequest" to ReceiptRequest.serializer(),
        "PlayStoreReceiptRequest" to PlayStoreReceiptRequest.serializer(),
        "AppStoreReceiptRequest" to AppStoreReceiptRequest.serializer(),
        "CustomerInfo" to CustomerInfo.serializer(),
        "Entitlement" to Entitlement.serializer(),
        "NonSubscriptionPurchase" to NonSubscriptionPurchase.serializer(),
        "ErrorResponse" to ErrorResponse.serializer(),
        "ErrorBody" to ErrorBody.serializer(),
        "Store" to Store.serializer(),
        "PeriodType" to PeriodType.serializer(),
        "ErrorCode" to ErrorCode.serializer(),
    )
