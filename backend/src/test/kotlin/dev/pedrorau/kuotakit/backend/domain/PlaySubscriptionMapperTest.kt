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

import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private fun map(
    subscription: PlaySubscription,
    productId: String = "premium_monthly",
    existing: Purchase? = null,
) = PlaySubscriptionMapper.toPurchase(subscription, productId, "token-1", existing, NOW)

class PlaySubscriptionMapperTest :
    StringSpec({
        "an active subscription becomes an active purchase with everything Google said" {
            val purchase = map(playSubscription(offerPhase = PlayOfferPhase.FREE_TRIAL))
            purchase shouldBe
                Purchase(
                    store = Store.PLAY_STORE,
                    productId = "premium_monthly",
                    token = "token-1",
                    kind = PurchaseKind.SUBSCRIPTION,
                    status = PurchaseStatus.ACTIVE,
                    purchasedAt = NOW - 20.days,
                    expiresAt = NOW + 10.days,
                    autoRenewing = true,
                    periodType = PeriodType.TRIAL,
                    billingIssueDetectedAt = null,
                    updatedAt = NOW,
                )
        }

        "access is granted exactly for the states Google says should keep access, while the period lasts" {
            val granting =
                listOf(
                    PlaySubscriptionState.ACTIVE,
                    PlaySubscriptionState.CANCELED,
                    PlaySubscriptionState.IN_GRACE_PERIOD,
                )
            granting.forEach { state ->
                map(playSubscription(state = state, expiryTime = NOW + 1.seconds)).status shouldBe PurchaseStatus.ACTIVE
                map(playSubscription(state = state, expiryTime = NOW)).status shouldBe PurchaseStatus.EXPIRED
                map(playSubscription(state = state, expiryTime = NOW - 5.days)).status shouldBe PurchaseStatus.EXPIRED
            }
        }

        "a cancelled subscription keeps access until it ends, and will not renew" {
            val purchase = map(playSubscription(state = PlaySubscriptionState.CANCELED, autoRenewing = false))
            purchase.status shouldBe PurchaseStatus.ACTIVE
            purchase.autoRenewing shouldBe false
        }

        "a subscription on hold, paused or expired never grants access, but is kept as it is" {
            map(playSubscription(state = PlaySubscriptionState.ON_HOLD)).status shouldBe PurchaseStatus.ON_HOLD
            map(playSubscription(state = PlaySubscriptionState.PAUSED)).status shouldBe PurchaseStatus.PAUSED
            map(playSubscription(state = PlaySubscriptionState.EXPIRED)).status shouldBe PurchaseStatus.EXPIRED
            // even if Google still shows a future end date for them
            map(
                playSubscription(state = PlaySubscriptionState.ON_HOLD, expiryTime = NOW + 30.days),
            ).grantsAccessAt(NOW) shouldBe
                false
            map(
                playSubscription(state = PlaySubscriptionState.PAUSED, expiryTime = NOW + 30.days),
            ).grantsAccessAt(NOW) shouldBe
                false
        }

        "a pending or unknown state is refused, not guessed" {
            listOf(
                PlaySubscriptionState.PENDING,
                PlaySubscriptionState.PENDING_PURCHASE_CANCELED,
                PlaySubscriptionState.UNKNOWN,
            ).forEach { state ->
                shouldThrow<PurchaseInvalidException> { map(playSubscription(state = state)) }
            }
        }

        "a subscription that should grant access but has no end date is refused, because it would never expire" {
            listOf(
                PlaySubscriptionState.ACTIVE,
                PlaySubscriptionState.CANCELED,
                PlaySubscriptionState.IN_GRACE_PERIOD,
            ).forEach { state ->
                shouldThrow<PurchaseInvalidException> { map(playSubscription(state = state, expiryTime = null)) }
            }
        }

        "the purchase must be for the product the app said" {
            shouldThrow<PurchaseInvalidException> {
                map(
                    playSubscription(productId = "other_plan"),
                    productId = "premium_monthly",
                )
            }
            map(playSubscription(productId = "premium_annual"), productId = "premium_annual").productId shouldBe
                "premium_annual"
        }

        "the offer phase tells a free trial and an introductory price from a normal period" {
            map(playSubscription(offerPhase = PlayOfferPhase.FREE_TRIAL)).periodType shouldBe PeriodType.TRIAL
            map(playSubscription(offerPhase = PlayOfferPhase.INTRODUCTORY_PRICE)).periodType shouldBe PeriodType.INTRO
            map(playSubscription(offerPhase = PlayOfferPhase.BASE_PRICE)).periodType shouldBe PeriodType.NORMAL
            map(playSubscription(offerPhase = PlayOfferPhase.OTHER)).periodType shouldBe PeriodType.NORMAL
        }

        "a payment problem is dated when first seen and kept while it lasts" {
            val first = map(playSubscription(state = PlaySubscriptionState.IN_GRACE_PERIOD))
            first.billingIssueDetectedAt shouldBe NOW
            val later =
                PlaySubscriptionMapper.toPurchase(
                    playSubscription(state = PlaySubscriptionState.ON_HOLD),
                    "premium_monthly",
                    "token-1",
                    first,
                    NOW + 3.days,
                )
            later.billingIssueDetectedAt shouldBe NOW
            // once it is recovered the problem is gone
            PlaySubscriptionMapper
                .toPurchase(
                    playSubscription(),
                    "premium_monthly",
                    "token-1",
                    later,
                    NOW + 4.days,
                ).billingIssueDetectedAt shouldBe
                null
        }

        "the start date comes from Google, then from what was stored, then from now" {
            map(playSubscription(startTime = NOW - 9.days)).purchasedAt shouldBe NOW - 9.days
            val stored = map(playSubscription())
            map(playSubscription(startTime = null), existing = stored).purchasedAt shouldBe stored.purchasedAt
            map(playSubscription(startTime = null)).purchasedAt shouldBe NOW
        }
    })
