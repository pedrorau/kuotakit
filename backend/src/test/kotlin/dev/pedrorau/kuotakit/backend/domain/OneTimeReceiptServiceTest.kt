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
import dev.pedrorau.kuotakit.backend.testing.playOneTime
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private const val PACKAGE = "com.acme.app"
private const val USER = "anon_1"

private class OneTimeSetup {
    val log = mutableListOf<String>()
    val google = FakePlayStoreApi(log)
    val repository = InMemoryPurchaseRepository()
    val service =
        ReceiptService(
            google,
            PACKAGE,
            RecordingPurchaseRepository(repository, log),
            FixedClock(),
            PendingEndingRepository.None,
        ) {
            it == "lifetime_unlock"
        }

    suspend fun submit(
        user: String = USER,
        productId: String = "lifetime_unlock",
        token: String = "token-1",
    ) = service.submit(user, PlayStoreReceiptRequest(PACKAGE, productId, token))

    suspend fun stored(user: String = USER) = repository.purchasesOf(user)
}

class OneTimeReceiptServiceTest :
    StringSpec({
        "a one-time product is read as one-time, acknowledged and then saved, in that order" {
            val s = OneTimeSetup()
            s.submit()
            s.log shouldBe
                listOf("getOneTime:token-1", "acknowledgeOneTime:lifetime_unlock:token-1", "save:$USER:token-1")
            val saved = s.stored().single()
            saved.kind shouldBe PurchaseKind.NON_CONSUMABLE
            saved.grantsAccessAt(NOW) shouldBe true
        }

        "any other product is still read as a subscription" {
            val s = OneTimeSetup()
            s.google.subscription = { playSubscription(acknowledged = true) }
            s.submit(productId = "premium_monthly")
            s.log shouldBe listOf("get:token-1", "save:$USER:token-1")
            s.stored().single().kind shouldBe PurchaseKind.SUBSCRIPTION
        }

        "a purchase Google already acknowledged is not acknowledged again, and sending it twice changes nothing" {
            val s = OneTimeSetup()
            s.google.oneTime = { playOneTime(acknowledged = true) }
            s.submit()
            val first = s.stored()
            s.submit()
            s.stored() shouldBe first
            s.log.none { it.startsWith("acknowledgeOneTime") } shouldBe true
        }

        "if the acknowledgement fails nothing is saved, so the app can retry" {
            val s = OneTimeSetup()
            s.google.acknowledgeFailure = StoreUnavailableException("down")
            shouldThrow<StoreUnavailableException> { s.submit() }
            s.stored() shouldBe emptyList()
        }

        "a payment pending, a cancelled purchase or a wrong product is refused and nothing is saved or acknowledged" {
            listOf(
                playOneTime(state = PlayOneTimeState.PENDING),
                playOneTime(state = PlayOneTimeState.CANCELLED),
                playOneTime(state = PlayOneTimeState.UNKNOWN),
                playOneTime(productId = "something_else"),
            ).forEach { purchase ->
                val s = OneTimeSetup()
                s.google.oneTime = { purchase }
                shouldThrow<PurchaseInvalidException> { s.submit() }
                s.stored() shouldBe emptyList()
                s.log.none { it.startsWith("acknowledge") } shouldBe true
            }
        }

        "a fully refunded purchase is kept as refunded and never acknowledged" {
            val s = OneTimeSetup()
            s.google.oneTime = { playOneTime(refundableQuantity = 0) }
            s.submit()
            s.stored().single().status shouldBe PurchaseStatus.REFUNDED
            s.log.none { it.startsWith("acknowledge") } shouldBe true
        }

        "the user attached to the purchase is the only one who can claim it" {
            val s = OneTimeSetup()
            s.google.oneTime = { playOneTime(obfuscatedAccountId = "anon_buyer") }
            shouldThrow<TokenAlreadyLinkedException> { s.submit(user = "anon_other") }
            s.stored("anon_other") shouldBe emptyList()
            s.submit(user = "anon_buyer")
            s.stored("anon_buyer").size shouldBe 1
        }

        "a token already stored for another user cannot be claimed" {
            val s = OneTimeSetup()
            s.submit(user = "owner")
            shouldThrow<TokenAlreadyLinkedException> { s.submit(user = "thief") }
            s.stored("thief") shouldBe emptyList()
        }

        "sending the receipt again after a refund brings nothing back" {
            val s = OneTimeSetup()
            val ended =
                purchase(
                    token = "token-1",
                    productId = "lifetime_unlock",
                    kind = PurchaseKind.NON_CONSUMABLE,
                    expiresAt = null,
                )
            s.repository.save(USER, ended.copy(status = PurchaseStatus.REFUNDED))
            s.google.oneTime = { playOneTime(acknowledged = false) }
            s.submit()
            s.stored().single().status shouldBe PurchaseStatus.REFUNDED
            s.log.none { it.startsWith("acknowledge") } shouldBe true
        }
    })
