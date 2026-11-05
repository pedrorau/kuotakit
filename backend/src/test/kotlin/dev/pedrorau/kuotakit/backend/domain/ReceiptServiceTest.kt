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
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.RecordingPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.contracts.AppStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days

private const val PACKAGE = "com.acme.app"
private const val USER = "anon_1"

private class Setup(
    configured: Boolean = true,
) {
    val log = mutableListOf<String>()
    val google = FakePlayStoreApi(log)
    val repository = InMemoryPurchaseRepository()
    val service =
        ReceiptService(
            playStore = if (configured) google else null,
            androidPackageName = if (configured) PACKAGE else null,
            purchases = RecordingPurchaseRepository(repository, log),
            clock = FixedClock(),
        )

    suspend fun submit(
        user: String = USER,
        packageName: String = PACKAGE,
        productId: String = "premium_monthly",
        token: String = "token-1",
    ) = service.submit(user, PlayStoreReceiptRequest(packageName, productId, token))

    suspend fun stored(user: String = USER) = repository.purchasesOf(user)
}

class ReceiptServiceTest :
    StringSpec({
        "a valid purchase is acknowledged and then saved, in that order" {
            val s = Setup()
            s.submit()
            s.log shouldBe listOf("get:token-1", "acknowledge:premium_monthly:token-1", "save:$USER:token-1")
            val saved = s.stored().single()
            saved.status shouldBe PurchaseStatus.ACTIVE
            saved.productId shouldBe "premium_monthly"
            saved.grantsAccessAt(NOW) shouldBe true
        }

        "a purchase Google already has acknowledged is not acknowledged again" {
            val s = Setup()
            s.google.subscription = { playSubscription(acknowledged = true) }
            s.submit()
            s.log shouldBe listOf("get:token-1", "save:$USER:token-1")
        }

        "sending the same receipt again changes nothing but the stored copy" {
            val s = Setup()
            s.submit()
            s.google.subscription = { playSubscription(acknowledged = true, expiryTime = NOW + 40.days) }
            s.submit()
            s.stored().size shouldBe 1
            s.stored().single().expiresAt shouldBe NOW + 40.days
            s.log.count { it.startsWith("acknowledge") } shouldBe 1
        }

        "if the acknowledgement fails nothing is saved, so the app can retry" {
            val s = Setup()
            s.google.acknowledgeFailure = StoreUnavailableException("Google is down")
            shouldThrow<StoreUnavailableException> { s.submit() }
            s.stored() shouldBe emptyList()
            s.log shouldBe listOf("get:token-1", "acknowledge:premium_monthly:token-1")
        }

        "if Google cannot be asked nothing is saved and nothing is acknowledged" {
            val s = Setup()
            s.google.subscription = { throw StoreUnavailableException("timeout") }
            shouldThrow<StoreUnavailableException> { s.submit() }
            s.stored() shouldBe emptyList()
            s.log shouldBe listOf("get:token-1")
        }

        "a purchase Google says is not valid is refused without saving or acknowledging anything" {
            val s = Setup()
            s.google.subscription = { throw PurchaseInvalidException("no such token") }
            shouldThrow<PurchaseInvalidException> { s.submit() }
            s.stored() shouldBe emptyList()
            s.log shouldBe listOf("get:token-1")
        }

        "a pending payment or a product that does not match is refused" {
            val pending = Setup()
            pending.google.subscription = { playSubscription(state = PlaySubscriptionState.PENDING) }
            shouldThrow<PurchaseInvalidException> { pending.submit() }
            pending.stored() shouldBe emptyList()

            val other = Setup()
            other.google.subscription = { playSubscription(productId = "cheap_plan") }
            shouldThrow<PurchaseInvalidException> { other.submit(productId = "premium_annual") }
            other.stored() shouldBe emptyList()
            other.log.none { it.startsWith("acknowledge") } shouldBe true
        }

        "an expired or held subscription is kept, but never acknowledged and never grants access" {
            val states =
                listOf(PlaySubscriptionState.EXPIRED, PlaySubscriptionState.ON_HOLD, PlaySubscriptionState.PAUSED)
            states.forEach { state ->
                val s = Setup()
                s.google.subscription = { playSubscription(state = state) }
                s.submit()
                s.stored().single().grantsAccessAt(NOW) shouldBe false
                s.log.none { it.startsWith("acknowledge") } shouldBe true
            }
        }

        "a receipt for another app is refused before Google is asked" {
            val s = Setup()
            shouldThrow<InvalidReceiptException> { s.submit(packageName = "com.evil.app") }
            s.log shouldBe emptyList()
        }

        "empty or oversized fields are refused before Google is asked" {
            val s = Setup()
            shouldThrow<InvalidReceiptException> { s.submit(token = "") }
            shouldThrow<InvalidReceiptException> { s.submit(token = "  ") }
            shouldThrow<InvalidReceiptException> { s.submit(productId = "") }
            shouldThrow<InvalidReceiptException> { s.submit(token = "t".repeat(4097)) }
            shouldThrow<InvalidReceiptException> { s.submit(productId = "p".repeat(4097)) }
            s.log shouldBe emptyList()
            s.submit(token = "t".repeat(4096)) // the longest allowed
            s.google.subscription = { playSubscription(productId = "p".repeat(4096)) }
            s.submit(productId = "p".repeat(4096), token = "token-2") // the longest allowed
        }

        "a token that belongs to another user cannot be claimed, and is not acknowledged" {
            val s = Setup()
            s.submit(user = "owner")
            s.log.clear()
            s.google.subscription = { playSubscription(acknowledged = false) }
            shouldThrow<TokenAlreadyLinkedException> { s.submit(user = "thief") }
            s.stored("thief") shouldBe emptyList()
            s.log shouldBe listOf("get:token-1")
        }

        "when the app attached a user to the purchase only that user can claim it" {
            val s = Setup()
            s.google.subscription = { playSubscription(obfuscatedAccountId = "anon_buyer") }
            shouldThrow<TokenAlreadyLinkedException> { s.submit(user = "anon_other") }
            s.stored("anon_other") shouldBe emptyList()
            s.log.none { it.startsWith("acknowledge") } shouldBe true
            s.submit(user = "anon_buyer")
            s.stored("anon_buyer").size shouldBe 1
        }

        "a purchase with no account attached can be claimed by whoever sends it first" {
            val s = Setup()
            s.google.subscription = { playSubscription(obfuscatedAccountId = null) }
            s.submit(user = "anon_first")
            s.stored("anon_first").size shouldBe 1
        }

        "without Google Play configured the receipt cannot be validated, so nothing is granted" {
            val s = Setup(configured = false)
            shouldThrow<StoreUnavailableException> { s.submit() }
            s.stored() shouldBe emptyList()
        }

        "App Store receipts are not supported yet" {
            val s = Setup()
            shouldThrow<InvalidReceiptException> {
                s.service.submit(
                    USER,
                    AppStoreReceiptRequest("com.acme.app", "jws"),
                )
            }
            s.log shouldBe emptyList()
        }

        "a grace period keeps access and dates the payment problem once" {
            val s = Setup()
            s.google.subscription = { playSubscription(state = PlaySubscriptionState.IN_GRACE_PERIOD) }
            s.submit()
            val saved = s.stored().single()
            saved.grantsAccessAt(NOW) shouldBe true
            saved.billingIssueDetectedAt shouldBe NOW
            saved.store shouldBe Store.PLAY_STORE
        }

        "a payment problem is dated the first time and kept when the app sends the receipt again" {
            val s = Setup()
            s.google.subscription =
                { playSubscription(state = PlaySubscriptionState.IN_GRACE_PERIOD, acknowledged = true) }
            s.submit()
            val firstSeen = s.stored().single().billingIssueDetectedAt
            firstSeen shouldBe NOW
            val later = FixedClock(NOW + 2.days)
            val service = ReceiptService(s.google, PACKAGE, s.repository, later)
            service.submit(USER, PlayStoreReceiptRequest(PACKAGE, "premium_monthly", "token-1"))
            s.stored().single().billingIssueDetectedAt shouldBe firstSeen
        }

        "a grace period whose end date already passed does not grant access" {
            val s = Setup()
            s.google.subscription = {
                playSubscription(
                    state = PlaySubscriptionState.IN_GRACE_PERIOD,
                    expiryTime = NOW - 1.days,
                    acknowledged = true,
                )
            }
            s.submit()
            s.stored().single().grantsAccessAt(NOW) shouldBe false
        }
    })
