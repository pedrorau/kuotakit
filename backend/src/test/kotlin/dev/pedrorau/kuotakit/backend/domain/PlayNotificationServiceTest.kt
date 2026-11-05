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
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

private const val PACKAGE = "com.acme.app"
private const val USER = "anon_1"
private const val TOKEN = "token-1"
private val LATER = NOW + 3.days

private class NotificationSetup(
    configured: Boolean = true,
) {
    val google = FakePlayStoreApi()
    val repository = InMemoryPurchaseRepository()
    val events = InMemoryProcessedEvents()
    val service =
        PlayNotificationService(
            playStore = if (configured) google else null,
            androidPackageName = PACKAGE,
            purchases = repository,
            events = events,
            clock = FixedClock(LATER),
        )

    /** A purchase the app already submitted. */
    suspend fun stored(
        status: PurchaseStatus = PurchaseStatus.ACTIVE,
        user: String = USER,
    ) = purchase(token = TOKEN, status = status, expiresAt = NOW + 10.days).also { repository.save(user, it) }

    suspend fun push(
        notification: PlayNotification,
        messageId: String = "m1",
        packageName: String = PACKAGE,
    ) = service.handle(PlayPush(messageId, packageName, notification))

    suspend fun current(user: String = USER) = repository.purchasesOf(user).single()
}

private val changed = PlayNotification.SubscriptionChanged(TOKEN)

class PlayNotificationServiceTest :
    StringSpec({
        "a change is read again from Google and the stored purchase follows it" {
            val s = NotificationSetup()
            s.stored()
            s.google.subscription = { playSubscription(expiryTime = NOW + 40.days, acknowledged = true) }
            s.push(changed) shouldBe NotificationOutcome.UPDATED
            s.google.calls shouldBe listOf("get:$TOKEN")
            val purchase = s.current()
            purchase.expiresAt shouldBe NOW + 40.days
            purchase.status shouldBe PurchaseStatus.ACTIVE
            purchase.updatedAt shouldBe LATER
        }

        "an expiry or a hold that Google reports takes access away" {
            listOf(
                PlaySubscriptionState.EXPIRED to PurchaseStatus.EXPIRED,
                PlaySubscriptionState.ON_HOLD to PurchaseStatus.ON_HOLD,
                PlaySubscriptionState.PAUSED to PurchaseStatus.PAUSED,
            ).forEach { (state, status) ->
                val s = NotificationSetup()
                s.stored()
                s.google.subscription = { playSubscription(state = state) }
                s.push(changed) shouldBe NotificationOutcome.UPDATED
                s.current().status shouldBe status
                s.current().grantsAccessAt(LATER) shouldBe false
            }
        }

        "a payment problem is dated when first seen and kept by the next notification" {
            val s = NotificationSetup()
            s.stored()
            s.google.subscription = { playSubscription(state = PlaySubscriptionState.IN_GRACE_PERIOD) }
            s.push(changed, "m1")
            s.current().billingIssueDetectedAt shouldBe LATER
            val later = PlayNotificationService(s.google, PACKAGE, s.repository, s.events, FixedClock(LATER + 1.days))
            later.handle(PlayPush("m2", PACKAGE, changed))
            s.current().billingIssueDetectedAt shouldBe LATER
        }

        "a refund and a revocation take access away without asking Google" {
            listOf(
                PlayNotification.PurchaseVoided(TOKEN) to PurchaseStatus.REFUNDED,
                PlayNotification.SubscriptionRevoked(TOKEN) to PurchaseStatus.REVOKED,
            ).forEach { (notification, status) ->
                val s = NotificationSetup()
                val before = s.stored()
                s.push(notification) shouldBe NotificationOutcome.UPDATED
                s.google.calls shouldBe emptyList()
                s.current() shouldBe before.copy(status = status, updatedAt = LATER)
                s.current().grantsAccessAt(NOW) shouldBe false
            }
        }

        "a purchase that ended for good stays as it is, and Google is not asked" {
            listOf(PurchaseStatus.REFUNDED, PurchaseStatus.REVOKED).forEach { final ->
                listOf(changed, PlayNotification.PurchaseVoided(TOKEN), PlayNotification.SubscriptionRevoked(TOKEN))
                    .forEachIndexed { i, notification ->
                        val s = NotificationSetup()
                        val before = s.stored(final)
                        s.push(notification, "m$i") shouldBe NotificationOutcome.ALREADY_FINAL
                        s.google.calls shouldBe emptyList()
                        s.current() shouldBe before
                    }
            }
        }

        "a token nobody submitted creates nothing and costs no call to Google" {
            val s = NotificationSetup()
            listOf(
                changed,
                PlayNotification.PurchaseVoided(TOKEN),
                PlayNotification.SubscriptionRevoked(TOKEN),
            ).forEachIndexed { i, notification ->
                s.push(notification, "m$i") shouldBe NotificationOutcome.UNKNOWN_PURCHASE
            }
            s.google.calls shouldBe emptyList()
            s.repository.ownerOf(Store.PLAY_STORE, TOKEN) shouldBe null
        }

        "only the owner's purchase changes" {
            val s = NotificationSetup()
            s.stored(user = "owner")
            val other = purchase(token = "token-2", expiresAt = NOW + 10.days)
            s.repository.save("someone_else", other)
            s.push(PlayNotification.PurchaseVoided(TOKEN))
            s.repository
                .purchasesOf("owner")
                .single()
                .status shouldBe PurchaseStatus.REFUNDED
            s.repository.purchasesOf("someone_else").single() shouldBe other
        }

        "the same message twice is handled once" {
            val s = NotificationSetup()
            s.stored()
            s.google.subscription = { playSubscription(acknowledged = true) }
            s.push(changed, "same") shouldBe NotificationOutcome.UPDATED
            s.push(changed, "same") shouldBe NotificationOutcome.DUPLICATE
            s.google.calls.size shouldBe 1
        }

        "a message is remembered only after it was handled, so a failure is retried" {
            val s = NotificationSetup()
            s.stored()
            s.google.subscription = { throw StoreUnavailableException("down") }
            shouldThrow<StoreUnavailableException> { s.push(changed, "retry") }
            s.events.seen(Store.PLAY_STORE, "retry") shouldBe false
            s.google.subscription = { playSubscription(acknowledged = true) }
            s.push(changed, "retry") shouldBe NotificationOutcome.UPDATED
            s.events.seen(Store.PLAY_STORE, "retry") shouldBe true
        }

        "a message nothing was done with is remembered too" {
            val s = NotificationSetup()
            s.push(PlayNotification.Ignored("test"), "t1") shouldBe NotificationOutcome.IGNORED
            s.events.seen(Store.PLAY_STORE, "t1") shouldBe true
            s.push(changed, "o1", packageName = "com.other.app") shouldBe NotificationOutcome.OTHER_APP
            s.events.seen(Store.PLAY_STORE, "o1") shouldBe true
        }

        "a notification for another app is ignored, even a refund" {
            val s = NotificationSetup()
            val before = s.stored()
            s.push(PlayNotification.PurchaseVoided(TOKEN), packageName = "com.other.app") shouldBe
                NotificationOutcome.OTHER_APP
            s.current() shouldBe before
            s.google.calls shouldBe emptyList()
        }

        "when Google no longer describes the purchase the stored one is left alone" {
            val failures: List<() -> Nothing> =
                listOf(
                    { throw PurchaseInvalidException("gone") },
                    { throw PurchaseInvalidException("pending") },
                )
            failures.forEach { failure ->
                val s = NotificationSetup()
                val before = s.stored()
                s.google.subscription = failure
                s.push(changed) shouldBe NotificationOutcome.STORE_REJECTED
                s.current() shouldBe before
            }
            val s = NotificationSetup()
            val before = s.stored()
            s.google.subscription = { playSubscription(state = PlaySubscriptionState.PENDING) }
            s.push(changed) shouldBe NotificationOutcome.STORE_REJECTED
            s.current() shouldBe before
        }

        "without Google configured a change cannot be read, so it is retried later" {
            val s = NotificationSetup(configured = false)
            s.stored()
            shouldThrow<StoreUnavailableException> { s.push(changed) }
            s.events.seen(Store.PLAY_STORE, "m1") shouldBe false
        }

        "a refund that lands while a slow renewal is being read is not undone by it" {
            val s = NotificationSetup()
            val before = s.stored()
            // Google answers the renewal slowly; while it does, the refund of the same purchase is applied.
            val slow =
                object : PlayStoreApi {
                    override suspend fun subscription(purchaseToken: String): PlaySubscription {
                        s.repository.save(USER, before.copy(status = PurchaseStatus.REFUNDED, updatedAt = LATER))
                        return playSubscription(expiryTime = NOW + 40.days, acknowledged = true)
                    }

                    override suspend fun acknowledge(
                        productId: String,
                        purchaseToken: String,
                    ) = Unit

                    override suspend fun oneTimePurchase(purchaseToken: String): PlayOneTimePurchase = error("not used")

                    override suspend fun acknowledgeOneTime(
                        productId: String,
                        purchaseToken: String,
                    ) = Unit
                }
            val service = PlayNotificationService(slow, PACKAGE, s.repository, s.events, FixedClock(LATER))
            service.handle(PlayPush("renewal", PACKAGE, changed))
            s.current().status shouldBe PurchaseStatus.REFUNDED
            s.current().grantsAccessAt(LATER) shouldBe false
        }
    })
