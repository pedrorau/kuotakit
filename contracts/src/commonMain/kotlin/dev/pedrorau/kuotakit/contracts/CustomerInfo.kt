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
import kotlin.time.Instant

/**
 * What a user has bought and what they may access. Returned by `GET /v1/subscribers/{app_user_id}` and by
 * `POST /v1/subscribers/{app_user_id}/receipts`. Entitlements come only from the backend (ADR 0003).
 */
@Serializable
public data class CustomerInfo(
    @SerialName("app_user_id") val appUserId: String,
    @SerialName("request_date") val requestDate: Instant,
    /** Entitlements by identifier, as configured in `entitlements.yaml`. */
    val entitlements: Map<String, Entitlement>,
    @SerialName("active_subscriptions") val activeSubscriptions: List<String>,
    @SerialName("non_subscription_purchases") val nonSubscriptionPurchases: List<NonSubscriptionPurchase>,
)

/** One entitlement of a user. Nullable fields are always present in the JSON, with `null` when they do not apply. */
@Serializable
public data class Entitlement(
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("product_id") val productId: String,
    val store: Store,
    @SerialName("period_type") val periodType: PeriodType,
    @SerialName("expires_at") val expiresAt: Instant?,
    @SerialName("will_renew") val willRenew: Boolean,
    @SerialName("billing_issue_detected_at") val billingIssueDetectedAt: Instant?,
)

/** A one-time purchase that is not a subscription (a non-consumable, ADR 0005). */
@Serializable
public data class NonSubscriptionPurchase(
    @SerialName("product_id") val productId: String,
    val store: Store,
    @SerialName("purchased_at") val purchasedAt: Instant,
)
