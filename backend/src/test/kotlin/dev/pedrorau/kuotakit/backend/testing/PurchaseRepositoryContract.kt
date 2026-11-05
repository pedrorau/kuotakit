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

package dev.pedrorau.kuotakit.backend.testing

import dev.pedrorau.kuotakit.backend.domain.NOW
import dev.pedrorau.kuotakit.backend.domain.PurchaseKind
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.backend.domain.TokenAlreadyLinkedException
import dev.pedrorau.kuotakit.backend.domain.purchase
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private fun unique(prefix: String) = "$prefix-${UUID.randomUUID()}"

/**
 * What every [PurchaseRepository] must do (ADR 0009). The in-memory fake and the Postgres implementation both run
 * it; each test uses its own user and token ids, so one repository can serve all of them.
 */
@Suppress("LongMethod")
fun StringSpec.purchaseRepositoryContract(repository: () -> PurchaseRepository) {
    "a user nobody has heard of has no purchases and owns no token" {
        repository().purchasesOf(unique("user")).shouldBeEmpty()
        repository().ownerOf(Store.PLAY_STORE, unique("token")) shouldBe null
    }

    "a saved purchase reads back exactly, with every field" {
        val user = unique("user")
        val saved =
            purchase(
                token = unique("token"),
                store = Store.APP_STORE,
                productId = "com.acme.premium.annual",
                kind = PurchaseKind.NON_CONSUMABLE,
                status = PurchaseStatus.REFUNDED,
                purchasedAt = Instant.fromEpochMilliseconds(1_790_000_000_123),
                expiresAt = Instant.fromEpochMilliseconds(1_795_000_000_456),
                autoRenewing = false,
                periodType = PeriodType.TRIAL,
                billingIssueDetectedAt = Instant.fromEpochMilliseconds(1_794_000_000_789),
                updatedAt = Instant.fromEpochMilliseconds(1_791_000_000_001),
            )
        repository().save(user, saved)
        repository().purchasesOf(user) shouldContainExactly listOf(saved)
    }

    "fields that are absent stay absent" {
        val user = unique("user")
        val saved = purchase(token = unique("token"), expiresAt = null, billingIssueDetectedAt = null)
        repository().save(user, saved)
        repository().purchasesOf(user) shouldContainExactly listOf(saved)
    }

    "purchases come back oldest first, then by token" {
        val user = unique("user")
        val old = purchase(token = "b-${UUID.randomUUID()}", purchasedAt = NOW - 30.days)
        val sameTimeA = purchase(token = "a-${UUID.randomUUID()}", purchasedAt = NOW - 10.days)
        val sameTimeB = purchase(token = "c-${UUID.randomUUID()}", purchasedAt = NOW - 10.days)
        listOf(sameTimeB, old, sameTimeA).forEach { repository().save(user, it) }
        repository().purchasesOf(user) shouldContainExactly listOf(old, sameTimeA, sameTimeB)
    }

    "saving the same purchase again replaces it instead of adding a row" {
        val user = unique("user")
        val token = unique("token")
        repository().save(user, purchase(token = token, status = PurchaseStatus.ACTIVE, expiresAt = NOW + 10.days))
        val renewed =
            purchase(token = token, status = PurchaseStatus.ACTIVE, expiresAt = NOW + 40.days, updatedAt = NOW)
        repository().save(user, renewed)
        repository().purchasesOf(user) shouldContainExactly listOf(renewed)
    }

    "a token that belongs to another user cannot be taken, and the owner keeps it untouched" {
        val owner = unique("owner")
        val thief = unique("thief")
        val token = unique("token")
        val original = purchase(token = token)
        repository().save(owner, original)

        shouldThrow<TokenAlreadyLinkedException> {
            repository().save(
                thief,
                purchase(token = token, productId = "stolen"),
            )
        }

        repository().ownerOf(Store.PLAY_STORE, token) shouldBe owner
        repository().purchasesOf(owner) shouldContainExactly listOf(original)
        repository().purchasesOf(thief).shouldBeEmpty()
    }

    "the same token string in the other store is a different purchase" {
        val token = unique("token")
        val play = purchase(token = token, store = Store.PLAY_STORE)
        val apple = purchase(token = token, store = Store.APP_STORE, productId = "com.acme.premium.monthly")
        val a = unique("a")
        val b = unique("b")
        repository().save(a, play)
        repository().save(b, apple)
        repository().ownerOf(Store.PLAY_STORE, token) shouldBe a
        repository().ownerOf(Store.APP_STORE, token) shouldBe b
    }

    "users only see their own purchases" {
        val a = unique("a")
        val b = unique("b")
        val forA = purchase(token = unique("ta"))
        val forB = purchase(token = unique("tb"))
        repository().save(a, forA)
        repository().save(b, forB)
        repository().purchasesOf(a) shouldContainExactly listOf(forA)
        repository().purchasesOf(b) shouldContainExactly listOf(forB)
    }

    "two users racing for one token: exactly one of them gets it" {
        val token = unique("token")
        val users = listOf(unique("u1"), unique("u2"), unique("u3"), unique("u4"))
        val outcomes =
            coroutineScope {
                users
                    .map { user ->
                        async {
                            runCatching { repository().save(user, purchase(token = token)) }.exceptionOrNull()
                        }
                    }.awaitAll()
            }
        outcomes.count { it == null } shouldBe 1
        outcomes.filterNotNull().forEach { it::class shouldBe TokenAlreadyLinkedException::class }
        val winner = repository().ownerOf(Store.PLAY_STORE, token)
        users.filter { it != winner }.forEach { repository().purchasesOf(it).shouldBeEmpty() }
    }
}
