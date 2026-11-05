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

import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

val NOW: Instant = Instant.parse("2026-11-01T12:00:00Z")

/** The configuration used by the domain tests: one entitlement sold in both stores and one lifetime unlock. */
val CONFIG =
    EntitlementsConfig(
        entitlements =
            linkedMapOf(
                "premium" to
                    EntitlementDefinition(
                        mapOf(
                            Store.PLAY_STORE to setOf("premium_monthly", "premium_annual"),
                            Store.APP_STORE to setOf("com.acme.premium.monthly", "com.acme.premium.annual"),
                        ),
                    ),
                "lifetime" to EntitlementDefinition(mapOf(Store.PLAY_STORE to setOf("pro_lifetime"))),
            ),
        currentOffering = "default",
        offerings =
            linkedMapOf(
                "default" to
                    OfferingDefinition(
                        "default",
                        linkedMapOf(
                            "monthly" to
                                PackageDefinition(
                                    mapOf(
                                        Store.PLAY_STORE to "premium_monthly",
                                        Store.APP_STORE to "com.acme.premium.monthly",
                                    ),
                                ),
                            "annual" to PackageDefinition(mapOf(Store.PLAY_STORE to "premium_annual")),
                        ),
                    ),
                "sale" to OfferingDefinition("sale", linkedMapOf()),
            ),
    )

/**
 * A purchase that grants access at [NOW]: an auto-renewing monthly subscription ending in 10 days. Every field has a
 * default, so a test names only what it is about.
 */
@Suppress("LongParameterList") // a builder with defaults: the long list is what keeps the tests short
fun purchase(
    productId: String = "premium_monthly",
    token: String = "token-1",
    store: Store = Store.PLAY_STORE,
    kind: PurchaseKind = PurchaseKind.SUBSCRIPTION,
    status: PurchaseStatus = PurchaseStatus.ACTIVE,
    purchasedAt: Instant = NOW - 20.days,
    expiresAt: Instant? = NOW + 10.days,
    autoRenewing: Boolean = true,
    periodType: PeriodType = PeriodType.NORMAL,
    billingIssueDetectedAt: Instant? = null,
    updatedAt: Instant = NOW - 1.days,
) = Purchase(
    store,
    productId,
    token,
    kind,
    status,
    purchasedAt,
    expiresAt,
    autoRenewing,
    periodType,
    billingIssueDetectedAt,
    updatedAt,
)
