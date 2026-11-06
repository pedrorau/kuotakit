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
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private const val PACKAGE = "com.acme.app"
private const val USER = "anon_1"
private const val TOKEN = "token-1"
private const val MESSAGE = "m1"
private val LATER = NOW + 3.days

/** Fails the n-th call to any dependency, once, so every step of an operation gets its turn to break. */
private class Fault {
    var failAt = -1
    var calls = 0

    suspend fun step() {
        calls++
        yield()
        check(calls != failAt) { "injected failure in call $calls" }
    }
}

/** The fault happens before the call does its work, or after it did (the caller never learns that it worked). */
private class Injector(
    val fault: Fault,
    val after: Boolean,
) {
    suspend fun <T> around(work: suspend () -> T): T {
        if (!after) fault.step()
        val result = work()
        if (after) fault.step()
        return result
    }
}

private class FaultyPurchases(
    private val inner: PurchaseRepository,
    private val i: Injector,
) : PurchaseRepository {
    override suspend fun purchasesOf(appUserId: String) = i.around { inner.purchasesOf(appUserId) }

    override suspend fun save(
        appUserId: String,
        purchase: Purchase,
    ) = i.around { inner.save(appUserId, purchase) }

    override suspend fun ownerOf(
        store: Store,
        token: String,
    ) = i.around { inner.ownerOf(store, token) }
}

private class FaultyEvents(
    private val inner: ProcessedEventRepository,
    private val i: Injector,
) : ProcessedEventRepository {
    override suspend fun seen(
        store: Store,
        messageId: String,
    ) = i.around { inner.seen(store, messageId) }

    override suspend fun record(
        store: Store,
        messageId: String,
        at: Instant,
    ) = i.around { inner.record(store, messageId, at) }
}

private class FaultyPending(
    private val inner: PendingEndingRepository,
    private val i: Injector,
) : PendingEndingRepository {
    override suspend fun record(
        store: Store,
        token: String,
        status: PurchaseStatus,
        at: Instant,
    ) = i.around { inner.record(store, token, status, at) }

    override suspend fun find(
        store: Store,
        token: String,
        now: Instant,
    ) = i.around { inner.find(store, token, now) }

    override suspend fun forget(
        store: Store,
        token: String,
    ) = i.around { inner.forget(store, token) }
}

private class FaultyGoogle(
    private val inner: PlayStoreApi,
    private val i: Injector,
) : PlayStoreApi {
    override suspend fun subscription(purchaseToken: String) = i.around { inner.subscription(purchaseToken) }

    override suspend fun acknowledge(
        productId: String,
        purchaseToken: String,
    ) = i.around { inner.acknowledge(productId, purchaseToken) }

    override suspend fun oneTimePurchase(purchaseToken: String) = i.around { inner.oneTimePurchase(purchaseToken) }

    override suspend fun acknowledgeOneTime(
        productId: String,
        purchaseToken: String,
    ) = i.around { inner.acknowledgeOneTime(productId, purchaseToken) }
}

/** What is left behind, ignoring how it got there. */
private data class Snapshot(
    val purchases: List<Purchase>,
    val pending: PurchaseStatus?,
    val messageSeen: Boolean,
)

private class World(
    val fault: Fault = Fault(),
    after: Boolean = false,
) {
    val google = FakePlayStoreApi()
    val repository = InMemoryPurchaseRepository()
    val events = InMemoryProcessedEvents()
    val pending = InMemoryPendingEndings()
    private val i = Injector(fault, after)
    private val clock = FixedClock(LATER)
    private val receipts =
        ReceiptService(
            FaultyGoogle(google, i),
            PACKAGE,
            FaultyPurchases(repository, i),
            clock,
            FaultyPending(pending, i),
        ) {
            it == "lifetime_unlock"
        }
    private val notifications =
        PlayNotificationService(
            FaultyGoogle(google, i),
            PACKAGE,
            FaultyPurchases(repository, i),
            FaultyEvents(events, i),
            clock,
            FaultyPending(pending, i),
        )

    suspend fun push(notification: PlayNotification) = notifications.handle(PlayPush(MESSAGE, PACKAGE, notification))

    suspend fun submit(
        productId: String,
        token: String = TOKEN,
    ) = receipts.submit(USER, PlayStoreReceiptRequest(PACKAGE, productId, token))

    suspend fun snapshot() =
        Snapshot(
            repository.purchasesOf(USER).sortedBy { it.token },
            pending.find(Store.PLAY_STORE, TOKEN, LATER),
            events.seen(Store.PLAY_STORE, MESSAGE),
        )
}

private class Scenario(
    val name: String,
    val seed: suspend World.() -> Unit,
    val act: suspend World.() -> Unit,
)

private val activeSubscription = purchase(token = TOKEN, expiresAt = NOW + 10.days)
private val lifetime =
    purchase(token = TOKEN, productId = "lifetime_unlock", kind = PurchaseKind.NON_CONSUMABLE, expiresAt = null)

private val SCENARIOS =
    listOf(
        Scenario(
            "a renewal notification for a stored subscription",
            seed = {
                repository.save(USER, activeSubscription)
                google.subscription = { playSubscription(expiryTime = NOW + 40.days, acknowledged = true) }
            },
            act = { push(PlayNotification.SubscriptionChanged(TOKEN)) },
        ),
        Scenario(
            "a refund notification for a stored subscription",
            seed = { repository.save(USER, activeSubscription) },
            act = { push(PlayNotification.PurchaseVoided(TOKEN)) },
        ),
        Scenario(
            "a refund notification for a purchase nobody submitted",
            seed = { },
            act = { push(PlayNotification.PurchaseVoided(TOKEN)) },
        ),
        Scenario(
            "a cancellation notification for a stored one-time purchase",
            seed = {
                repository.save(USER, lifetime)
                google.oneTime = { playOneTime(state = PlayOneTimeState.CANCELLED, acknowledged = true) }
            },
            act = { push(PlayNotification.OneTimeChanged(TOKEN)) },
        ),
        Scenario(
            "a new subscription receipt",
            seed = { google.subscription = { playSubscription(acknowledged = false) } },
            act = { submit("premium_monthly") },
        ),
        Scenario(
            "a new one-time receipt",
            seed = { google.oneTime = { playOneTime(acknowledged = false) } },
            act = { submit("lifetime_unlock") },
        ),
        Scenario(
            "a receipt for a purchase refunded before it arrived",
            seed = {
                pending.record(Store.PLAY_STORE, TOKEN, PurchaseStatus.REFUNDED, NOW)
                google.subscription = { playSubscription(acknowledged = false) }
            },
            act = { submit("premium_monthly") },
        ),
        Scenario(
            "an upgrade receipt that replaces another subscription",
            seed = {
                repository.save(USER, purchase(token = "old-token", expiresAt = NOW + 10.days))
                google.subscription = {
                    playSubscription(
                        productId = "premium_annual",
                        acknowledged = false,
                        linkedPurchaseToken = "old-token",
                    )
                }
            },
            act = { submit("premium_annual") },
        ),
    )

class FaultToleranceTest :
    StringSpec({
        // The property: if any call to a dependency fails, at any step and whether or not its work was done, the
        // failure reaches the caller (so the store or the app retries) and trying again leaves exactly what an
        // attempt without failures leaves. Nothing is lost, nothing is applied twice, and no failure is swallowed.
        SCENARIOS.forEach { scenario ->
            listOf(false, true).forEach { after ->
                val moment = if (after) "after its work was done" else "before its work"
                "${scenario.name}: a failure of any step, $moment, is raised and a retry gives the normal result" {
                    val reference = World(Fault())
                    reference.seed(scenario.seed)
                    reference.act(scenario.act)
                    val steps = reference.fault.calls
                    val expected = reference.snapshot()
                    (steps > 1) shouldBe true

                    (1..steps).forEach { n ->
                        val world = World(Fault().apply { failAt = n }, after)
                        world.seed(scenario.seed)
                        world.fault.calls = 0
                        shouldThrow<IllegalStateException> { world.act(scenario.act) }
                        world.fault.failAt = -1
                        world.act(scenario.act)
                        world.snapshot() shouldBe expected
                    }
                }
            }
        }
    })

private suspend fun World.seed(seed: suspend World.() -> Unit) = seed()

private suspend fun World.act(act: suspend World.() -> Unit) = act()
