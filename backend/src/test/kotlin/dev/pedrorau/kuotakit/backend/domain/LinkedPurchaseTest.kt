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

import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.FixedClock
import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

private const val PACKAGE = "com.acme.app"
private const val USER = "anon_1"

private class UpgradeSetup {
    val google = FakePlayStoreApi()
    val repository = InMemoryPurchaseRepository()
    val receipts = ReceiptService(google, PACKAGE, repository, FixedClock(), PendingEndingRepository.None)
    val notifications =
        PlayNotificationService(
            google,
            PACKAGE,
            repository,
            InMemoryProcessedEvents(),
            FixedClock(),
            PendingEndingRepository.None,
        )

    /** The plan the user had before. */
    suspend fun oldPurchase(
        status: PurchaseStatus = PurchaseStatus.ACTIVE,
        user: String = USER,
    ) = purchase(token = "old-token", productId = "premium_monthly", status = status, expiresAt = NOW + 10.days)
        .also { repository.save(user, it) }

    suspend fun submitNew(
        user: String = USER,
        productId: String = "premium_annual",
    ) = receipts.submit(user, PlayStoreReceiptRequest(PACKAGE, productId, "new-token"))

    suspend fun purchaseOf(
        token: String,
        user: String = USER,
    ) = repository.purchasesOf(user).first { it.token == token }
}

class LinkedPurchaseTest :
    StringSpec({
        "a purchase that replaces another ends the old one as of now, and the new one gives access" {
            val s = UpgradeSetup()
            s.oldPurchase()
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "old-token")
            }
            s.submitNew()
            val old = s.purchaseOf("old-token")
            old.status shouldBe PurchaseStatus.EXPIRED
            old.expiresAt shouldBe NOW
            old.grantsAccessAt(NOW) shouldBe false
            s.purchaseOf("new-token").grantsAccessAt(NOW) shouldBe true
        }

        "the old purchase is left alone if the new one is refused, so the user is never left with nothing" {
            val s = UpgradeSetup()
            val before = s.oldPurchase()
            s.google.subscription = {
                playSubscription(
                    productId = "premium_annual",
                    state = PlaySubscriptionState.PENDING,
                    linkedPurchaseToken = "old-token",
                )
            }
            shouldThrow<PurchaseInvalidException> { s.submitNew() }
            s.purchaseOf("old-token") shouldBe before
        }

        "the old purchase is left alone if saving the new one fails" {
            val s = UpgradeSetup()
            s.oldPurchase()
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = false, linkedPurchaseToken = "old-token")
            }
            s.google.acknowledgeFailure = StoreUnavailableException("down")
            shouldThrow<StoreUnavailableException> { s.submitNew() }
            s.purchaseOf("old-token").status shouldBe PurchaseStatus.ACTIVE
        }

        "nothing happens when the old token is unknown, absent, or already without access" {
            val s = UpgradeSetup()
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "never-seen")
            }
            s.submitNew()
            s.repository.purchasesOf(USER).size shouldBe 1

            val ended = UpgradeSetup()
            val held = ended.oldPurchase(PurchaseStatus.ON_HOLD)
            ended.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "old-token")
            }
            ended.submitNew()
            ended.purchaseOf("old-token") shouldBe held
        }

        "a refunded old purchase keeps its reason" {
            val s = UpgradeSetup()
            val refunded = s.oldPurchase(PurchaseStatus.REFUNDED)
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "old-token")
            }
            s.submitNew()
            s.purchaseOf("old-token") shouldBe refunded
        }

        "the old purchase ends even when it belongs to another user, because Google says it moved" {
            val s = UpgradeSetup()
            s.oldPurchase(user = "previous_owner")
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "old-token")
            }
            s.submitNew()
            s.purchaseOf("old-token", "previous_owner").status shouldBe PurchaseStatus.EXPIRED
        }

        "a notification for the new token ends the old one too" {
            val s = UpgradeSetup()
            s.oldPurchase()
            s.repository.save(
                USER,
                purchase(token = "new-token", productId = "premium_annual", expiresAt = NOW + 1.days),
            )
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "old-token")
            }
            s.notifications.handle(PlayPush("m1", PACKAGE, PlayNotification.SubscriptionChanged("new-token"))) shouldBe
                NotificationOutcome.UPDATED
            s.purchaseOf("old-token").status shouldBe PurchaseStatus.EXPIRED
        }

        "a notification Google no longer describes leaves the old purchase alone" {
            val s = UpgradeSetup()
            val before = s.oldPurchase()
            s.repository.save(
                USER,
                purchase(token = "new-token", productId = "premium_annual", expiresAt = NOW + 1.days),
            )
            s.google.subscription = {
                playSubscription(
                    productId = "premium_annual",
                    state = PlaySubscriptionState.PENDING,
                    linkedPurchaseToken = "old-token",
                )
            }
            s.notifications.handle(PlayPush("m1", PACKAGE, PlayNotification.SubscriptionChanged("new-token"))) shouldBe
                NotificationOutcome.STORE_REJECTED
            s.purchaseOf("old-token") shouldBe before
        }

        "a purchase that names itself as the one it replaced is not ended" {
            val s = UpgradeSetup()
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "new-token")
            }
            s.submitNew()
            s.purchaseOf("new-token").grantsAccessAt(NOW) shouldBe true
        }

        "only a subscription can be replaced by a subscription" {
            val s = UpgradeSetup()
            val lifetime =
                purchase(
                    token = "old-token",
                    productId = "lifetime_unlock",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    expiresAt = null,
                )
            s.repository.save(USER, lifetime)
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "old-token")
            }
            s.submitNew()
            s.purchaseOf("old-token") shouldBe lifetime
        }

        "an upgrade whose new purchase carries no account id is refused, and the plan the user has is left alone" {
            val s = UpgradeSetup()
            val before = s.oldPurchase()
            s.google.subscription = {
                playSubscription(
                    productId = "premium_annual",
                    acknowledged = false,
                    obfuscatedAccountId = null,
                    linkedPurchaseToken = "old-token",
                )
            }
            shouldThrow<PurchaseInvalidException> { s.submitNew() }
            s.purchaseOf("old-token") shouldBe before
            s.repository.purchasesOf(USER).size shouldBe 1
        }
    })
