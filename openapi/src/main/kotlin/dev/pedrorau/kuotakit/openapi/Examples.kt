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
import dev.pedrorau.kuotakit.contracts.Offering
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.StoreProducts
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/**
 * The examples of docs/api-spec.md, built from the models themselves, so an example can never disagree with the
 * wire format: they are serialized with the same serializers the backend and the SDK use.
 */
object Examples {
    private val json = kuotaKitJson

    private fun <T> encode(
        serializer: KSerializer<T>,
        value: T,
    ): JsonElement = json.encodeToJsonElement(serializer, value)

    val health: JsonElement = encode(HealthResponse.serializer(), HealthResponse("ok"))

    val offerings: JsonElement =
        encode(
            OfferingsResponse.serializer(),
            OfferingsResponse(
                current = "default",
                offerings =
                    listOf(
                        Offering(
                            "default",
                            listOf(
                                OfferingPackage(
                                    "monthly",
                                    StoreProducts("premium_monthly", "com.acme.premium.monthly"),
                                ),
                                OfferingPackage("annual", StoreProducts("premium_annual", "com.acme.premium.annual")),
                            ),
                        ),
                    ),
            ),
        )

    val customerInfo: JsonElement =
        encode(
            CustomerInfo.serializer(),
            CustomerInfo(
                appUserId = "\$anon_6f1c",
                requestDate = Instant.parse("2026-11-01T12:00:00Z"),
                entitlements =
                    mapOf(
                        "premium" to
                            Entitlement(
                                isActive = true,
                                productId = "premium_monthly",
                                store = Store.PLAY_STORE,
                                periodType = PeriodType.NORMAL,
                                expiresAt = Instant.parse("2026-12-01T12:00:00Z"),
                                willRenew = true,
                                billingIssueDetectedAt = null,
                            ),
                    ),
                activeSubscriptions = listOf("premium_monthly"),
                nonSubscriptionPurchases = emptyList(),
            ),
        )

    val playStoreReceipt: JsonElement =
        encode(
            ReceiptRequest.serializer(),
            PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "opaque-purchase-token"),
        )

    val appStoreReceipt: JsonElement =
        encode(ReceiptRequest.serializer(), AppStoreReceiptRequest("com.acme.app", "eyJhbGciOiJFUzI1NiJ9..."))

    fun error(
        code: ErrorCode,
        message: String,
    ): JsonElement = encode(ErrorResponse.serializer(), ErrorResponse(ErrorBody(code, message)))
}
