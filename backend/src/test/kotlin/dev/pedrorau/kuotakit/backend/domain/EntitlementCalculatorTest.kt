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

import dev.pedrorau.kuotakit.contracts.Entitlement
import dev.pedrorau.kuotakit.contracts.NonSubscriptionPurchase
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private val calculator = EntitlementCalculator(CONFIG)

private fun info(vararg purchases: Purchase) = calculator.customerInfo("anon_1", purchases.toList(), NOW)

private fun premium(vararg purchases: Purchase): Entitlement? = info(*purchases).entitlements["premium"]

class EntitlementCalculatorTest :
    StringSpec({
        "a user with no purchases has nothing, and the request is echoed" {
            val result = info()
            result.appUserId shouldBe "anon_1"
            result.requestDate shouldBe NOW
            result.entitlements.keys.shouldBeEmpty()
            result.activeSubscriptions.shouldBeEmpty()
            result.nonSubscriptionPurchases.shouldBeEmpty()
        }

        "an active subscription grants its entitlement with the details of the purchase" {
            val result =
                premium(
                    purchase(
                        periodType = PeriodType.TRIAL,
                        billingIssueDetectedAt = NOW - 1.days,
                    ),
                )
            result shouldBe
                Entitlement(
                    isActive = true,
                    productId = "premium_monthly",
                    store = Store.PLAY_STORE,
                    periodType = PeriodType.TRIAL,
                    expiresAt = NOW + 10.days,
                    willRenew = true,
                    billingIssueDetectedAt = NOW - 1.days,
                )
        }

        "access ends exactly when the period ends, not a moment later" {
            premium(purchase(expiresAt = NOW + 1.seconds))?.isActive shouldBe true
            premium(purchase(expiresAt = NOW))?.isActive shouldBe false
            premium(purchase(expiresAt = NOW - 1.seconds))?.isActive shouldBe false
        }

        "a purchase the store does not report as active never grants access, even inside its period" {
            listOf(PurchaseStatus.EXPIRED, PurchaseStatus.REFUNDED, PurchaseStatus.REVOKED).forEach { status ->
                premium(purchase(status = status))?.isActive shouldBe false
            }
        }

        "an entitlement the user once had stays visible but inactive" {
            val result = premium(purchase(status = PurchaseStatus.REFUNDED, expiresAt = NOW + 10.days))
            result?.isActive shouldBe false
            result?.willRenew shouldBe false
            result?.productId shouldBe "premium_monthly"
        }

        "an entitlement the user never had is not listed" {
            info(purchase()).entitlements.keys shouldBe setOf("premium")
        }

        "it will only renew while it is active and set to renew" {
            premium(purchase(autoRenewing = true))?.willRenew shouldBe true
            premium(purchase(autoRenewing = false))?.willRenew shouldBe false
            premium(purchase(autoRenewing = true, expiresAt = NOW - 1.days))?.willRenew shouldBe false
        }

        "among purchases that grant access the one lasting longest decides" {
            val short = purchase(token = "a", expiresAt = NOW + 2.days, productId = "premium_monthly")
            val long = purchase(token = "b", expiresAt = NOW + 300.days, productId = "premium_annual")
            premium(short, long)?.productId shouldBe "premium_annual"
            premium(long, short)?.productId shouldBe "premium_annual"
        }

        "a purchase that grants access beats a longer one that does not" {
            val expiredButLonger = purchase(token = "a", status = PurchaseStatus.REFUNDED, expiresAt = NOW + 900.days)
            val active = purchase(token = "b", productId = "premium_annual", expiresAt = NOW + 5.days)
            val entitlement = premium(expiredButLonger, active)
            entitlement?.productId shouldBe "premium_annual"
            entitlement?.isActive shouldBe true
        }

        "ties on the end date are broken by the latest update, then by the token, so the answer is stable" {
            val older = purchase(token = "a", productId = "premium_monthly", updatedAt = NOW - 5.days)
            val newer = purchase(token = "b", productId = "premium_annual", updatedAt = NOW - 1.days)
            premium(older, newer)?.productId shouldBe "premium_annual"
            premium(newer, older)?.productId shouldBe "premium_annual"

            val tokenA = purchase(token = "a", productId = "premium_monthly")
            val tokenB = purchase(token = "b", productId = "premium_annual")
            premium(tokenA, tokenB)?.productId shouldBe "premium_annual"
            premium(tokenB, tokenA)?.productId shouldBe "premium_annual"
        }

        "when nothing grants access the one that lasted longest is shown" {
            val old = purchase(token = "a", productId = "premium_monthly", expiresAt = NOW - 30.days)
            val recent = purchase(token = "b", productId = "premium_annual", expiresAt = NOW - 2.days)
            val entitlement = premium(old, recent)
            entitlement?.productId shouldBe "premium_annual"
            entitlement?.isActive shouldBe false
        }

        "the same entitlement can come from either store" {
            val play = purchase(token = "p", expiresAt = NOW + 3.days)
            val apple =
                purchase(
                    token = "a",
                    store = Store.APP_STORE,
                    productId = "com.acme.premium.annual",
                    expiresAt =
                        NOW + 100.days,
                )
            val entitlement = premium(play, apple)
            entitlement?.store shouldBe Store.APP_STORE
            entitlement?.productId shouldBe "com.acme.premium.annual"
        }

        "a product id only counts in the store it is configured for" {
            premium(purchase(store = Store.APP_STORE, productId = "premium_monthly")) shouldBe null
        }

        "a product that is not configured grants no entitlement but still counts as an active subscription" {
            val result = info(purchase(productId = "mystery_plan"))
            result.entitlements.keys.shouldBeEmpty()
            result.activeSubscriptions shouldBe listOf("mystery_plan")
        }

        "a lifetime purchase never expires" {
            val lifetime =
                purchase(
                    productId = "pro_lifetime",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    expiresAt = null,
                    autoRenewing = false,
                )
            val entitlement = info(lifetime).entitlements["lifetime"]
            entitlement?.isActive shouldBe true
            entitlement?.expiresAt shouldBe null
            entitlement?.willRenew shouldBe false
        }

        "a purchase without an end date lasts longer than any dated one" {
            val dated = purchase(token = "a", productId = "premium_annual", expiresAt = NOW + 4000.days)
            val forever =
                purchase(
                    token = "b",
                    productId = "premium_monthly",
                    expiresAt = null,
                    kind = PurchaseKind.NON_CONSUMABLE,
                )
            premium(dated, forever)?.productId shouldBe "premium_monthly"
        }

        "active subscriptions are the distinct products that grant access, sorted" {
            val result =
                info(
                    purchase(token = "1", productId = "premium_monthly"),
                    purchase(token = "2", productId = "premium_monthly"),
                    purchase(token = "3", productId = "premium_annual"),
                    purchase(token = "4", productId = "expired_one", expiresAt = NOW - 1.days),
                    purchase(token = "5", productId = "refunded_one", status = PurchaseStatus.REFUNDED),
                    purchase(
                        token = "6",
                        productId = "pro_lifetime",
                        kind = PurchaseKind.NON_CONSUMABLE,
                        expiresAt = null,
                    ),
                )
            result.activeSubscriptions shouldBe listOf("premium_annual", "premium_monthly")
        }

        "non-subscription purchases list only the active one-time purchases, oldest first" {
            val first =
                purchase(
                    token = "1",
                    productId = "b_pack",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    purchasedAt =
                        NOW - 30.days,
                    expiresAt = null,
                )
            val second =
                purchase(
                    token = "2",
                    productId = "a_pack",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    purchasedAt =
                        NOW - 30.days,
                    expiresAt = null,
                )
            val third =
                purchase(
                    token = "3",
                    productId = "c_pack",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    purchasedAt =
                        NOW - 5.days,
                    expiresAt = null,
                    store = Store.APP_STORE,
                )
            val refunded =
                purchase(
                    token = "4",
                    productId = "d_pack",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    status = PurchaseStatus.REFUNDED,
                    expiresAt = null,
                )
            val subscription = purchase(token = "5")
            info(third, refunded, subscription, first, second).nonSubscriptionPurchases shouldBe
                listOf(
                    NonSubscriptionPurchase("a_pack", Store.PLAY_STORE, NOW - 30.days),
                    NonSubscriptionPurchase("b_pack", Store.PLAY_STORE, NOW - 30.days),
                    NonSubscriptionPurchase("c_pack", Store.APP_STORE, NOW - 5.days),
                )
        }

        "entitlements come in the order of the configuration" {
            val lifetime =
                purchase(token = "l", productId = "pro_lifetime", kind = PurchaseKind.NON_CONSUMABLE, expiresAt = null)
            info(lifetime, purchase()).entitlements.keys.toList() shouldBe listOf("premium", "lifetime")
        }
    })
