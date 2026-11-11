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

package dev.pedrorau.kuotakit.sdk.e2e

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.sdk.KuotaKit
import dev.pedrorau.kuotakit.sdk.KuotaKitConfig
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.PlanChange
import dev.pedrorau.kuotakit.sdk.PurchaseOutcome
import dev.pedrorau.kuotakit.sdk.ReplacementMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun kit(
    play: FakePlay,
    store: MemoryStore = MemoryStore(),
    baseUrl: String = World.backend,
    key: String = World.APP_KEY,
) = KuotaKit.configure(KuotaKitConfig(baseUrl, key, play, store))

private suspend fun KuotaKit.pkg(id: String): OfferingPackage =
    getOfferings()
        .offerings
        .first()
        .packages
        .first { it.id == id }

private fun PurchaseOutcome.info(): CustomerInfo = (this as PurchaseOutcome.Success).customerInfo

/**
 * The SDK as an app uses it, against the real reference backend and the fake Google (docker-compose.conformance.yml).
 * A fake Play stands in for Google Play: it reports to the fake Google what Play would, using the account id that the
 * SDK attached. So these tests prove that what the SDK sends is what the backend accepts, with no device.
 */
class SdkAgainstBackendTest :
    StringSpec({
        "a subscription bought through the SDK gives premium: the backend accepts its account id" {
            val play = FakePlay()
            val kit = kit(play)
            val info = kit.purchase(kit.pkg("monthly")).info()
            val token = play.purchases.single().token

            info.entitlements.getValue("premium").isActive shouldBe true
            info.entitlements.getValue("premium").productId shouldBe "premium_monthly"
            info.activeSubscriptions shouldBe listOf("premium_monthly")
            kit.customerInfo.value shouldBe info
            val acknowledged =
                Google.calls().filter {
                    it.startsWith("POST") && it.endsWith(":acknowledge") &&
                        token in it
                }
            acknowledged.size shouldBe 1
        }

        "a product bought once gives its entitlement and is listed as a purchase" {
            val kit = kit(FakePlay())
            val info = kit.purchase(kit.pkg("lifetime")).info()

            info.entitlements.getValue("lifetime").isActive shouldBe true
            info.nonSubscriptionPurchases.map { it.productId } shouldBe listOf("lifetime_unlock")
        }

        "a pending payment grants nothing, and once approved a restore finds it" {
            val play = FakePlay().apply { screen = Screen.PENDING }
            val before = play.ownedCalls.get()
            val kit = kit(play)
            play.settle(before)

            kit.purchase(kit.pkg("monthly")).shouldBeInstanceOf<PurchaseOutcome.Pending>()
            // The sync at start already read the customer info: a pending payment must not have changed it.
            (
                kit.customerInfo.value
                    ?.entitlements
                    ?.size ?: 0
            ) shouldBe 0
            kit.getCustomerInfo().entitlements.size shouldBe 0

            play.approve()
            val restored = kit.restore()
            restored.submitted shouldBe 1
            restored.customerInfo.entitlements
                .getValue("premium")
                .isActive shouldBe true
        }

        "a user who cancels the purchase screen gets an outcome and the backend is never asked for a receipt" {
            val play = FakePlay().apply { screen = Screen.CANCEL }
            val kit = kit(play)
            kit.purchase(kit.pkg("monthly")).shouldBeInstanceOf<PurchaseOutcome.Cancelled>()
            play.purchases.size shouldBe 0
            // The sync at start may or may not have read the customer info yet; either way it grants nothing.
            (
                kit.customerInfo.value
                    ?.entitlements
                    ?.size ?: 0
            ) shouldBe 0
            kit.getCustomerInfo().entitlements.size shouldBe 0
        }

        "a refund that Google reports takes the access away, and the SDK shows what the backend says" {
            val play = FakePlay()
            val kit = kit(play)
            kit
                .purchase(kit.pkg("monthly"))
                .info()
                .entitlements
                .getValue("premium")
                .isActive shouldBe true

            Google.refund(play.purchases.single().token) shouldBe 204

            kit
                .getCustomerInfo()
                .entitlements
                .getValue("premium")
                .isActive shouldBe false
        }

        "a purchase that carries the account id of another user is refused as a purchase of another user" {
            val kit = kit(FakePlay().apply { accountOverride = "0".repeat(64) })
            shouldThrow<KuotaKitException.TokenAlreadyLinked> { kit.purchase(kit.pkg("monthly")) }
            kit.getCustomerInfo().entitlements.size shouldBe 0
        }

        "a purchase with no account id is refused: an SDK that forgot to attach it would be caught here" {
            val kit = kit(FakePlay().apply { accountOverride = "" })
            shouldThrow<KuotaKitException.PurchaseInvalid> { kit.purchase(kit.pkg("monthly")) }
            kit.getCustomerInfo().entitlements.size shouldBe 0
        }

        "an upgrade carries the account id and replaces the old plan" {
            val play = FakePlay()
            val kit = kit(play)
            kit.purchase(kit.pkg("monthly"))

            val change = PlanChange(kit.pkg("monthly"), ReplacementMode.CHARGE_PRORATED_PRICE)
            val info = kit.purchase(kit.pkg("annual"), change).info()

            info.activeSubscriptions shouldBe listOf("premium_annual")
            info.entitlements.getValue("premium").productId shouldBe "premium_annual"
        }

        "a deferred downgrade keeps the old plan until it ends, without renewing" {
            val play = FakePlay()
            val kit = kit(play)
            kit.purchase(kit.pkg("annual"))

            val info = kit.purchase(kit.pkg("monthly"), PlanChange(kit.pkg("annual"), ReplacementMode.DEFERRED)).info()

            val premium = info.entitlements.getValue("premium")
            premium.isActive shouldBe true
            premium.productId shouldBe "premium_annual"
            premium.willRenew shouldBe false
            info.activeSubscriptions shouldBe listOf("premium_annual")
        }

        "a purchase whose receipt could not be sent is found again and sent by a restore" {
            val play = FakePlay()
            val store = MemoryStore()
            val monthly = kit(play, store).pkg("monthly")
            val offline = kit(play, store, baseUrl = "http://localhost:9")

            shouldThrow<KuotaKitException.Network> { offline.purchase(monthly) }
            play.purchases.size shouldBe 1

            // The new SDK syncs at start: the store hides the purchase until the restore of the test finds it.
            play.hideOwned = true
            val before = play.ownedCalls.get()
            val second = kit(play, store)
            play.settle(before)
            play.hideOwned = false
            val restored = second.restore()
            restored.submitted shouldBe 1
            restored.customerInfo.entitlements
                .getValue("premium")
                .isActive shouldBe true
        }

        "what the backend answered is kept for the next run with no network, and is never an answer of the backend" {
            val play = FakePlay()
            val store = MemoryStore()
            val online = kit(play, store)
            online.purchase(online.pkg("monthly"))

            val offline = kit(play, store, baseUrl = "http://localhost:9")
            val cached = offline.lastKnownCustomerInfo.value
            cached
                ?.customerInfo
                ?.entitlements
                ?.getValue("premium")
                ?.isActive shouldBe true
            offline.customerInfo.value shouldBe null
            shouldThrow<KuotaKitException.Network> { offline.getCustomerInfo() }
        }

        "a wrong API key reaches the app as Unauthorized, and a secret key is never accepted by the SDK" {
            val unknown = kit(FakePlay(), key = "pk_this_key_is_not_known_by_the_backend")
            shouldThrow<KuotaKitException.Unauthorized> { unknown.getOfferings() }
            shouldThrow<KuotaKitException.InvalidConfiguration> { kit(FakePlay(), key = "sk_conformance_secret_key_x") }
        }

        "an app that buys again and again is told to wait by the backend, with how long" {
            val kit = kit(FakePlay())
            val monthly = kit.pkg("monthly")
            // More than two windows of 30 a minute, so that a boundary of the limiter inside the loop cannot hide it.
            val limited = shouldThrow<KuotaKitException.RateLimited> { repeat(70) { kit.purchase(monthly) } }
            ((limited.retryAfterSeconds ?: 0) >= 1) shouldBe true
        }
    })
