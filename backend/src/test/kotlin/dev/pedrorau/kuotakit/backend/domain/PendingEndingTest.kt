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
import dev.pedrorau.kuotakit.backend.testing.InMemoryPendingEndings
import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.playOneTime
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private const val PACKAGE = "com.acme.app"
private const val USER = "anon_1"
private const val TOKEN = "token-1"

private class EarlyRefundSetup(
    val pending: PendingEndingRepository = InMemoryPendingEndings(),
) {
    val google = FakePlayStoreApi()
    val repository = InMemoryPurchaseRepository()
    val events = InMemoryProcessedEvents()
    val receipts = ReceiptService(google, PACKAGE, repository, FixedClock(), pending) { it == "lifetime_unlock" }
    val notifications = PlayNotificationService(google, PACKAGE, repository, events, FixedClock(), pending)

    suspend fun push(
        notification: PlayNotification,
        id: String = "m1",
    ) = notifications.handle(PlayPush(id, PACKAGE, notification))

    suspend fun submit(
        productId: String = "premium_monthly",
        token: String = TOKEN,
    ) = receipts.submit(USER, PlayStoreReceiptRequest(PACKAGE, productId, token))

    suspend fun current() = repository.purchasesOf(USER).single()
}

class PendingEndingTest :
    StringSpec({
        "a refund that comes before the receipt is applied when the receipt arrives, without acknowledging it" {
            val s = EarlyRefundSetup()
            s.push(PlayNotification.PurchaseVoided(TOKEN)) shouldBe NotificationOutcome.RECORDED_FOR_LATER
            s.repository.ownerOf(Store.PLAY_STORE, TOKEN) shouldBe null
            // Google still describes it as a paid, unacknowledged subscription
            s.google.subscription = { playSubscription(acknowledged = false) }
            s.submit()
            s.current().status shouldBe PurchaseStatus.REFUNDED
            s.current().grantsAccessAt(NOW) shouldBe false
            s.google.calls.none { it.startsWith("acknowledge") } shouldBe true
            s.pending.find(Store.PLAY_STORE, TOKEN, NOW) shouldBe null
        }

        "a revocation that comes before the receipt is kept as a revocation" {
            val s = EarlyRefundSetup()
            s.push(PlayNotification.SubscriptionRevoked(TOKEN)) shouldBe NotificationOutcome.RECORDED_FOR_LATER
            s.google.subscription = { playSubscription(acknowledged = false) }
            s.submit()
            s.current().status shouldBe PurchaseStatus.REVOKED
        }

        "it works for a one-time purchase too" {
            val s = EarlyRefundSetup()
            s.push(PlayNotification.PurchaseVoided(TOKEN)) shouldBe NotificationOutcome.RECORDED_FOR_LATER
            s.google.oneTime = { playOneTime(acknowledged = false) }
            s.submit(productId = "lifetime_unlock")
            s.current().status shouldBe PurchaseStatus.REFUNDED
            s.google.calls.none { it.startsWith("acknowledgeOneTime") } shouldBe true
        }

        "the first reason is the one that counts" {
            val s = EarlyRefundSetup()
            s.push(PlayNotification.PurchaseVoided(TOKEN), "a")
            s.push(PlayNotification.SubscriptionRevoked(TOKEN), "b")
            s.google.subscription = { playSubscription(acknowledged = true) }
            s.submit()
            s.current().status shouldBe PurchaseStatus.REFUNDED
        }

        "only the purchase that was reported is affected" {
            val s = EarlyRefundSetup()
            s.push(PlayNotification.PurchaseVoided("another-token"))
            s.google.subscription = { playSubscription(acknowledged = true) }
            s.submit()
            s.current().status shouldBe PurchaseStatus.ACTIVE
            s.pending.find(Store.PLAY_STORE, "another-token", NOW) shouldBe PurchaseStatus.REFUNDED
        }

        "a change that is not a refund is not remembered, because the receipt reads fresh state anyway" {
            val s = EarlyRefundSetup()
            s.push(PlayNotification.SubscriptionChanged(TOKEN), "a") shouldBe NotificationOutcome.UNKNOWN_PURCHASE
            s.push(PlayNotification.OneTimeChanged(TOKEN), "b") shouldBe NotificationOutcome.UNKNOWN_PURCHASE
            s.pending.find(Store.PLAY_STORE, TOKEN, NOW) shouldBe null
        }

        "if the receipt is refused the refund keeps waiting" {
            val s = EarlyRefundSetup()
            s.push(PlayNotification.PurchaseVoided(TOKEN))
            s.google.subscription = { playSubscription(state = PlaySubscriptionState.PENDING) }
            shouldThrow<PurchaseInvalidException> { s.submit() }
            s.pending.find(Store.PLAY_STORE, TOKEN, NOW) shouldBe PurchaseStatus.REFUNDED
            s.repository.purchasesOf(USER) shouldBe emptyList()
        }

        "the purchase a refunded one replaced is not ended" {
            val s = EarlyRefundSetup()
            val old = purchase(token = "old-token", productId = "premium_monthly", expiresAt = NOW + 10.days)
            s.repository.save(USER, old)
            s.push(PlayNotification.PurchaseVoided(TOKEN))
            s.google.subscription = {
                playSubscription(productId = "premium_annual", acknowledged = true, linkedPurchaseToken = "old-token")
            }
            s.submit(productId = "premium_annual")
            s.repository.purchasesOf(USER).first { it.token == "old-token" } shouldBe old
        }

        "a refund and a receipt that cross each other are both seen" {
            // The receipt is stored right after the refund was written down, before the refund looks again.
            val repository = InMemoryPurchaseRepository()
            val inner = InMemoryPendingEndings()
            val pending =
                object : PendingEndingRepository by inner {
                    override suspend fun record(
                        store: Store,
                        token: String,
                        status: PurchaseStatus,
                        at: Instant,
                    ) {
                        inner.record(store, token, status, at)
                        repository.save(USER, purchase(token = token, expiresAt = NOW + 10.days))
                    }
                }
            val service =
                PlayNotificationService(
                    FakePlayStoreApi(),
                    PACKAGE,
                    repository,
                    InMemoryProcessedEvents(),
                    FixedClock(),
                    pending,
                )

            service.handle(PlayPush("m1", PACKAGE, PlayNotification.PurchaseVoided(TOKEN))) shouldBe
                NotificationOutcome.UPDATED

            repository.purchasesOf(USER).single().status shouldBe PurchaseStatus.REFUNDED
            inner.find(Store.PLAY_STORE, TOKEN, NOW) shouldBe null
        }

        "a refund that lands while the receipt waits for Google is applied as soon as the purchase is stored" {
            val repository = InMemoryPurchaseRepository()
            val pending = InMemoryPendingEndings()
            val notifications =
                PlayNotificationService(
                    FakePlayStoreApi(),
                    PACKAGE,
                    repository,
                    InMemoryProcessedEvents(),
                    FixedClock(),
                    pending,
                )
            var outcome: NotificationOutcome? = null
            // The receipt has looked for a pending refund and found none, and is acknowledging with Google: that is a
            // network round trip, and the refund arrives in the middle of it, when the purchase is not stored yet.
            val google =
                object : PlayStoreApi {
                    override suspend fun subscription(purchaseToken: String) = playSubscription(acknowledged = false)

                    override suspend fun acknowledge(
                        productId: String,
                        purchaseToken: String,
                    ) {
                        outcome =
                            notifications.handle(PlayPush("m-late", PACKAGE, PlayNotification.PurchaseVoided(TOKEN)))
                    }

                    override suspend fun oneTimePurchase(purchaseToken: String): PlayOneTimePurchase = error("not used")

                    override suspend fun acknowledgeOneTime(
                        productId: String,
                        purchaseToken: String,
                    ) = Unit
                }
            val receipts = ReceiptService(google, PACKAGE, repository, FixedClock(), pending)

            receipts.submit(USER, PlayStoreReceiptRequest(PACKAGE, "premium_monthly", TOKEN))

            outcome shouldBe NotificationOutcome.RECORDED_FOR_LATER
            repository.purchasesOf(USER).single().status shouldBe PurchaseStatus.REFUNDED
            pending.find(Store.PLAY_STORE, TOKEN, NOW) shouldBe null
        }

        "a refund older than the retention is not applied to a receipt that comes later" {
            val s = EarlyRefundSetup()
            s.pending.record(
                Store.PLAY_STORE,
                TOKEN,
                PurchaseStatus.REFUNDED,
                NOW - PendingEndingRepository.RETENTION - 1.days,
            )
            s.google.subscription = { playSubscription(acknowledged = true) }
            s.submit()
            s.current().status shouldBe PurchaseStatus.ACTIVE
        }
    })
