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

import dev.pedrorau.kuotakit.backend.testing.playOneTime
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

private fun map(
    purchase: PlayOneTimePurchase,
    productId: String = "lifetime_unlock",
    existing: Purchase? = null,
) = PlayOneTimeMapper.toPurchase(purchase, productId, "token-1", existing, NOW)

class PlayOneTimeMapperTest :
    StringSpec({
        "a completed purchase becomes an active purchase that never expires" {
            map(playOneTime()) shouldBe
                Purchase(
                    store = Store.PLAY_STORE,
                    productId = "lifetime_unlock",
                    token = "token-1",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    status = PurchaseStatus.ACTIVE,
                    purchasedAt = NOW - 2.days,
                    expiresAt = null,
                    autoRenewing = false,
                    periodType = PeriodType.NORMAL,
                    billingIssueDetectedAt = null,
                    updatedAt = NOW,
                )
            map(playOneTime()).grantsAccessAt(NOW + 3650.days) shouldBe true
        }

        "something left to refund means it is still the user's" {
            map(playOneTime(refundableQuantity = 1)).status shouldBe PurchaseStatus.ACTIVE
            map(playOneTime(refundableQuantity = 3)).status shouldBe PurchaseStatus.ACTIVE
        }

        "a missing quantity is not read as nothing refunded, because a zero is often left out of JSON" {
            shouldThrow<PurchaseInvalidException> { map(playOneTime(refundableQuantity = null)) }
            shouldThrow<PurchaseInvalidException> {
                map(playOneTime(refundableQuantity = null), existing = map(playOneTime()))
            }
        }

        "a negative quantity is no quantity left either" {
            map(playOneTime(refundableQuantity = -1)).status shouldBe PurchaseStatus.REFUNDED
        }

        "nothing left to refund means it was refunded" {
            val refunded = map(playOneTime(refundableQuantity = 0))
            refunded.status shouldBe PurchaseStatus.REFUNDED
            refunded.grantsAccessAt(NOW) shouldBe false
        }

        "a cancelled purchase we hold is revoked, and one we never held does not exist" {
            val held = map(playOneTime())
            map(playOneTime(state = PlayOneTimeState.CANCELLED), existing = held).status shouldBe PurchaseStatus.REVOKED
            shouldThrow<PurchaseInvalidException> { map(playOneTime(state = PlayOneTimeState.CANCELLED)) }
        }

        "a payment still pending or a state nobody knows is refused, not guessed" {
            listOf(PlayOneTimeState.PENDING, PlayOneTimeState.UNKNOWN).forEach { state ->
                shouldThrow<PurchaseInvalidException> { map(playOneTime(state = state)) }
                shouldThrow<PurchaseInvalidException> { map(playOneTime(state = state), existing = map(playOneTime())) }
            }
        }

        "the purchase must be for the product the receipt names" {
            shouldThrow<PurchaseInvalidException> {
                map(
                    playOneTime(productId = "other"),
                    productId = "lifetime_unlock",
                )
            }
            map(playOneTime(productId = "pack"), productId = "pack").productId shouldBe "pack"
        }

        "the purchase date is Google's, then what was stored, then now" {
            map(playOneTime(completionTime = NOW - 9.days)).purchasedAt shouldBe NOW - 9.days
            val stored = map(playOneTime())
            map(playOneTime(completionTime = null), existing = stored).purchasedAt shouldBe stored.purchasedAt
            map(playOneTime(completionTime = null)).purchasedAt shouldBe NOW
        }
    })
