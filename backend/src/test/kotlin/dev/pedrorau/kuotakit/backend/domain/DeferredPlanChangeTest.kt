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

import dev.pedrorau.kuotakit.backend.testing.DEFAULT_BUYER
import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.FixedClock
import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private const val PACKAGE = "com.acme.app"
private const val USER = DEFAULT_BUYER
private const val OLD = "premium_annual"
private const val NEW = "premium_monthly"

/**
 * A subscription changed with `ReplacementMode.DEFERRED` (docs/adr/0020): the old item stays, with auto-renew off,
 * until its end; the item that replaces it is not owned yet and has no end date.
 */
@Suppress("LongParameterList") // the fields of Google's answer that a test varies
private fun deferred(
    oldEnds: Instant? = NOW + 10.days,
    newEnds: Instant? = null,
    deferredTo: String? = NEW,
    account: String? = playObfuscatedAccountId(USER),
    newOwned: Boolean = false,
    state: PlaySubscriptionState = PlaySubscriptionState.ACTIVE,
) = PlaySubscription(
    state = state,
    startTime = NOW - 1.days,
    items =
        listOf(
            PlaySubscriptionItem(
                OLD,
                oldEnds,
                autoRenewing = false,
                offerPhase = PlayOfferPhase.BASE_PRICE,
                deferredReplacement = deferredTo,
            ),
            PlaySubscriptionItem(
                NEW,
                newEnds,
                autoRenewing = true,
                offerPhase = PlayOfferPhase.BASE_PRICE,
                ownedByUser = newOwned,
            ),
        ),
    acknowledged = false,
    obfuscatedAccountId = account,
    linkedPurchaseToken = "old-token",
)

private class DeferredSetup {
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

    /** The plan the user had before the change. */
    suspend fun before() =
        repository.save(
            USER,
            purchase(
                token = "old-token",
                productId = OLD,
                expiresAt =
                    NOW + 10.days,
            ),
        )

    suspend fun submit(
        productId: String = NEW,
        user: String = USER,
    ) = receipts.submit(user, PlayStoreReceiptRequest(PACKAGE, productId, "new-token"))

    suspend fun renewalNotification() =
        notifications.handle(
            PlayPush(
                "m-${repository.purchasesOf(USER).size}",
                PACKAGE,
                PlayNotification.SubscriptionChanged("new-token"),
            ),
        )

    suspend fun stored(token: String) = repository.purchasesOf(USER).single { it.token == token }
}

class DeferredPlanChangeTest :
    StringSpec({
        "the old plan lasts whichever product the receipt names, and is acknowledged as that product" {
            listOf(NEW, OLD).forEach { named ->
                val s = DeferredSetup()
                s.before()
                s.google.subscription = { deferred() }
                s.submit(named)

                val saved = s.stored("new-token")
                saved.productId shouldBe OLD
                saved.expiresAt shouldBe NOW + 10.days
                saved.autoRenewing shouldBe false
                saved.status shouldBe PurchaseStatus.ACTIVE
                saved.grantsAccessAt(NOW) shouldBe true
                s.google.calls.filter { it.startsWith("acknowledge") } shouldBe listOf("acknowledge:$OLD:new-token")
                s.stored("old-token").grantsAccessAt(NOW) shouldBe false
            }
        }

        "once the old plan has ended the new one is what the user has, from a notification and from a receipt" {
            val s = DeferredSetup()
            s.before()
            s.google.subscription = { deferred() }
            s.submit()

            s.google.subscription =
                { deferred(oldEnds = NOW - 1.days, newEnds = NOW + 30.days, newOwned = true).copy(acknowledged = true) }
            s.renewalNotification() shouldBe NotificationOutcome.UPDATED
            s.stored("new-token").productId shouldBe NEW
            s.stored("new-token").expiresAt shouldBe NOW + 30.days
            s.stored("new-token").autoRenewing shouldBe true

            listOf(NEW, OLD).forEach { named ->
                val other = DeferredSetup()
                other.google.subscription =
                    { deferred(oldEnds = NOW - 1.days, newEnds = NOW + 30.days, newOwned = true) }
                other.submit(named)
                other.stored("new-token").productId shouldBe NEW
            }
        }

        "at the very moment the old plan ends the new one is the plan the user has, whichever product is named" {
            listOf(NEW, OLD).forEach { named ->
                val s = DeferredSetup()
                s.google.subscription = { deferred(oldEnds = NOW, newEnds = NOW + 30.days, newOwned = true) }
                s.submit(named)
                s.stored("new-token").productId shouldBe NEW
                s.stored("new-token").grantsAccessAt(NOW) shouldBe true
            }
        }

        "a change that cannot be read is refused and nothing is acknowledged" {
            val unreadable =
                mapOf(
                    "the new item has no end date and the old one ended" to
                        deferred(oldEnds = NOW - 1.days, newEnds = null),
                    "the old item has no end date" to deferred(oldEnds = null),
                    "the old item says it is replaced by a product that is not in the purchase" to
                        deferred(deferredTo = "premium_weekly"),
                )
            unreadable.values.forEach { subscription ->
                val s = DeferredSetup()
                s.before()
                s.google.subscription = { subscription }
                shouldThrow<PurchaseInvalidException> { s.submit() }
                s.google.calls.none { it.startsWith("acknowledge") } shouldBe true
                s.repository.purchasesOf(USER).map { it.token } shouldBe listOf("old-token")
            }
        }

        "an item the user does not own yet is never the plan they have, even if it carries an end date" {
            val s = DeferredSetup()
            s.google.subscription =
                { deferred(oldEnds = NOW - 1.days, newEnds = NOW + 30.days, newOwned = false, deferredTo = null) }
            shouldThrow<PurchaseInvalidException> { s.submit() }
            s.google.calls.none { it.startsWith("acknowledge") } shouldBe true
        }

        "two items that both match are refused, never chosen by their order" {
            val s = DeferredSetup()
            val ambiguous =
                deferred().let {
                    it.copy(
                        items =
                            it.items +
                                PlaySubscriptionItem(
                                    "premium_weekly",
                                    NOW + 5.days,
                                    false,
                                    PlayOfferPhase.BASE_PRICE,
                                    NEW,
                                ),
                    )
                }
            s.google.subscription = { ambiguous }
            shouldThrow<PurchaseInvalidException> { s.submit() }
            // The old plan has ended and two items are the plan that replaces it: which one is not guessed.
            val twins =
                deferred(oldEnds = NOW - 1.days, newEnds = NOW + 30.days, newOwned = true).let {
                    it.copy(
                        items =
                            it.items + it.items[1],
                    )
                }
            s.google.subscription = { twins }
            shouldThrow<PurchaseInvalidException> { s.submit() }
        }

        "a state that does not grant, grants nothing whatever the old item says" {
            val notGranting =
                listOf(PlaySubscriptionState.EXPIRED, PlaySubscriptionState.PAUSED, PlaySubscriptionState.ON_HOLD)
            notGranting.forEach { state ->
                val s = DeferredSetup()
                s.google.subscription = { deferred(state = state) }
                s.submit()
                s.stored("new-token").grantsAccessAt(NOW) shouldBe false
            }
        }

        "an old plan that ends far in the future is the plan the user has" {
            val s = DeferredSetup()
            s.google.subscription = { deferred(oldEnds = NOW + 400.days) }
            s.submit()
            s.stored("new-token").productId shouldBe OLD
            s.stored("new-token").expiresAt shouldBe NOW + 400.days
        }

        "when Google no longer lists the old plan, a notification still gives the new one" {
            val s = DeferredSetup()
            s.before()
            s.google.subscription = { deferred() }
            s.submit()

            val onlyTheNewPlan =
                deferred(oldEnds = NOW - 1.days, newEnds = NOW + 30.days, newOwned = true)
                    .let { it.copy(items = listOf(it.items[1]), acknowledged = true) }
            s.google.subscription = { onlyTheNewPlan }
            s.renewalNotification() shouldBe NotificationOutcome.UPDATED
            s.stored("new-token").productId shouldBe NEW
            s.stored("new-token").grantsAccessAt(NOW) shouldBe true
        }

        "a notification for a purchase that has no item of its product and no single owned item changes nothing" {
            val s = DeferredSetup()
            s.google.subscription = { deferred() }
            s.submit()
            s.google.subscription =
                { deferred().let { it.copy(items = it.items.map { item -> item.copy(ownedByUser = false) }) } }
            s.renewalNotification() shouldBe NotificationOutcome.STORE_REJECTED
            s.stored("new-token").productId shouldBe OLD
        }

        "a revocation of a deferred purchase is final too" {
            val s = DeferredSetup()
            s.google.subscription = { deferred() }
            s.submit()
            s.notifications.handle(
                PlayPush("revoke", PACKAGE, PlayNotification.SubscriptionRevoked("new-token")),
            ) shouldBe
                NotificationOutcome.UPDATED
            s.submit()
            s.stored("new-token").status shouldBe PurchaseStatus.REVOKED
        }

        "a receipt for a product the purchase does not have is refused even after a deferred change" {
            val s = DeferredSetup()
            s.google.subscription = { deferred() }
            shouldThrow<PurchaseInvalidException> { s.submit("premium_weekly") }
        }

        "a deferred change follows the rules of ownership" {
            val s = DeferredSetup()
            s.google.subscription = { deferred(account = null) }
            shouldThrow<PurchaseInvalidException> { s.submit() }

            s.google.subscription = { deferred(account = playObfuscatedAccountId("anon_somebody_else")) }
            shouldThrow<TokenAlreadyLinkedException> { s.submit() }
            s.repository.purchasesOf(USER) shouldBe emptyList()
        }

        "a refund of a deferred purchase is final" {
            val s = DeferredSetup()
            s.google.subscription = { deferred() }
            s.submit()
            s.notifications.handle(PlayPush("refund", PACKAGE, PlayNotification.PurchaseVoided("new-token"))) shouldBe
                NotificationOutcome.UPDATED

            s.submit()
            s.stored("new-token").status shouldBe PurchaseStatus.REFUNDED
        }

        "a cycle of replacements in Google's answer ends and is not followed forever" {
            val cycle =
                deferred(oldEnds = NOW - 1.days, newEnds = NOW - 1.days).let {
                    it.copy(
                        items =
                            listOf(
                                it.items[0].copy(deferredReplacement = NEW),
                                it.items[1].copy(deferredReplacement = OLD),
                            ),
                    )
                }
            val s = DeferredSetup()
            s.google.subscription = { cycle }
            s.submit()
            s.stored("new-token").grantsAccessAt(NOW) shouldBe false
        }
    })
