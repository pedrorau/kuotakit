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
import dev.pedrorau.kuotakit.backend.testing.playOneTime
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

private const val PACKAGE = "com.acme.app"
private const val USER = "anon_1"
private val LATER = NOW + 3.days
private val changed = PlayNotification.OneTimeChanged("token-1")

private class OneTimeNotificationSetup {
    val google = FakePlayStoreApi()
    val repository = InMemoryPurchaseRepository()
    val service =
        PlayNotificationService(
            google,
            PACKAGE,
            repository,
            InMemoryProcessedEvents(),
            FixedClock(LATER),
            PendingEndingRepository.None,
        )

    suspend fun stored() =
        purchase(token = "token-1", productId = "lifetime_unlock", kind = PurchaseKind.NON_CONSUMABLE, expiresAt = null)
            .also { repository.save(USER, it) }

    suspend fun push(
        notification: PlayNotification,
        id: String = "m1",
    ) = service.handle(PlayPush(id, PACKAGE, notification))

    suspend fun current() = repository.purchasesOf(USER).single()
}

class OneTimeNotificationTest :
    StringSpec({
        "a cancelled one-time purchase is read from the one-time API and revoked" {
            val s = OneTimeNotificationSetup()
            s.stored()
            s.google.oneTime = { playOneTime(state = PlayOneTimeState.CANCELLED) }
            s.push(changed) shouldBe NotificationOutcome.UPDATED
            s.google.calls shouldBe listOf("getOneTime:token-1")
            s.current().status shouldBe PurchaseStatus.REVOKED
            s.current().grantsAccessAt(LATER) shouldBe false
        }

        "a partial refund leaves the purchase active, a full one ends it" {
            val s = OneTimeNotificationSetup()
            val before = s.stored()
            // Google does not repeat the completion time here: the stored date stays
            s.google.oneTime = { playOneTime(refundableQuantity = 1, completionTime = null) }
            s.push(changed, "partial")
            s.current().status shouldBe PurchaseStatus.ACTIVE
            s.current().purchasedAt shouldBe before.purchasedAt
            s.google.oneTime = { playOneTime(refundableQuantity = 0) }
            s.push(changed, "full")
            s.current().status shouldBe PurchaseStatus.REFUNDED
        }

        "a voided one-time purchase is a refund without asking Google" {
            val s = OneTimeNotificationSetup()
            s.stored()
            s.push(PlayNotification.PurchaseVoided("token-1")) shouldBe NotificationOutcome.UPDATED
            s.google.calls shouldBe emptyList()
            s.current().status shouldBe PurchaseStatus.REFUNDED
        }

        "a one-time purchase nobody submitted creates nothing" {
            val s = OneTimeNotificationSetup()
            s.push(changed) shouldBe NotificationOutcome.UNKNOWN_PURCHASE
            s.google.calls shouldBe emptyList()
        }

        "a purchase that ended for good is not read again" {
            val s = OneTimeNotificationSetup()
            s.repository.save(USER, s.stored().copy(status = PurchaseStatus.REVOKED))
            s.google.oneTime = { playOneTime() }
            s.push(changed) shouldBe NotificationOutcome.ALREADY_FINAL
            s.google.calls shouldBe emptyList()
            s.current().status shouldBe PurchaseStatus.REVOKED
        }

        "when Google stops confirming a one-time purchase it gives no access until a read confirms it again" {
            listOf<() -> PlayOneTimePurchase>(
                { throw PurchaseInvalidException("gone") },
                { playOneTime(state = PlayOneTimeState.UNKNOWN) },
                { playOneTime(state = PlayOneTimeState.PENDING) },
                { playOneTime(productId = "something_else") },
                { playOneTime(refundableQuantity = null) },
            ).forEachIndexed { i, answer ->
                val s = OneTimeNotificationSetup()
                s.stored()
                s.google.oneTime = answer
                s.push(changed, "a$i") shouldBe NotificationOutcome.STORE_REJECTED
                s.current().grantsAccessAt(LATER) shouldBe false
                s.current().status shouldBe PurchaseStatus.EXPIRED
                // not final: the next good read brings it back
                s.google.oneTime = { playOneTime(acknowledged = true) }
                s.push(changed, "b$i") shouldBe NotificationOutcome.UPDATED
                s.current().grantsAccessAt(LATER) shouldBe true
            }
        }

        "an outage is retried and changes nothing" {
            val s = OneTimeNotificationSetup()
            val before = s.stored()
            s.google.oneTime = { throw StoreUnavailableException("down") }
            shouldThrow<StoreUnavailableException> { s.push(changed) }
            s.current() shouldBe before
        }

        "a notification is read with the API of the stored kind, whatever the notification says" {
            val sub = OneTimeNotificationSetup()
            sub.repository.save(USER, purchase(token = "token-1", expiresAt = NOW + 10.days))
            sub.google.subscription = { playSubscription(acknowledged = true, expiryTime = NOW + 40.days) }
            sub.push(PlayNotification.OneTimeChanged("token-1")) shouldBe NotificationOutcome.UPDATED
            sub.google.calls shouldBe listOf("get:token-1")
            sub.current().expiresAt shouldBe NOW + 40.days

            val once = OneTimeNotificationSetup()
            once.stored()
            once.google.oneTime = { playOneTime(acknowledged = true) }
            once.push(PlayNotification.SubscriptionChanged("token-1")) shouldBe NotificationOutcome.UPDATED
            once.google.calls shouldBe listOf("getOneTime:token-1")
        }
    })
