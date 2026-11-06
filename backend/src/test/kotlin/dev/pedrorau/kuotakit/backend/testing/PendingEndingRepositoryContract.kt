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
import dev.pedrorau.kuotakit.backend.domain.PendingEndingRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private fun unique() = "token-${UUID.randomUUID()}"

/** What every [PendingEndingRepository] must do. The in-memory fake and the Postgres implementation both run it. */
fun StringSpec.pendingEndingRepositoryContract(repository: () -> PendingEndingRepository) {
    "nothing is pending for a purchase nobody reported" {
        repository().find(Store.PLAY_STORE, unique(), NOW) shouldBe null
    }

    "a recorded refund or revocation is found, only for its own store and token" {
        val token = unique()
        repository().record(Store.PLAY_STORE, token, PurchaseStatus.REFUNDED, NOW)
        repository().find(Store.PLAY_STORE, token, NOW) shouldBe PurchaseStatus.REFUNDED
        repository().find(Store.APP_STORE, token, NOW) shouldBe null
        repository().find(Store.PLAY_STORE, unique(), NOW) shouldBe null
        val other = unique()
        repository().record(Store.PLAY_STORE, other, PurchaseStatus.REVOKED, NOW)
        repository().find(Store.PLAY_STORE, other, NOW) shouldBe PurchaseStatus.REVOKED
    }

    "the first reason is kept" {
        val token = unique()
        repository().record(Store.PLAY_STORE, token, PurchaseStatus.REFUNDED, NOW)
        repository().record(Store.PLAY_STORE, token, PurchaseStatus.REVOKED, NOW + 1.seconds)
        repository().find(Store.PLAY_STORE, token, NOW) shouldBe PurchaseStatus.REFUNDED
    }

    "only a final status can wait for a receipt" {
        PurchaseStatus.entries.filter { !it.isFinal() }.forEach { status ->
            shouldThrow<IllegalArgumentException> { repository().record(Store.PLAY_STORE, unique(), status, NOW) }
        }
    }

    "forgetting removes it, and forgetting what is not there is harmless" {
        val token = unique()
        repository().record(Store.PLAY_STORE, token, PurchaseStatus.REVOKED, NOW)
        repository().forget(Store.PLAY_STORE, token)
        repository().find(Store.PLAY_STORE, token, NOW) shouldBe null
        repository().forget(Store.PLAY_STORE, token)
    }

    "one is forgotten once it is older than the retention, and not before" {
        val old = unique()
        val edge = unique()
        val recent = unique()
        val t = NOW + 200.days
        repository().record(
            Store.PLAY_STORE,
            old,
            PurchaseStatus.REFUNDED,
            t - PendingEndingRepository.RETENTION - 1.seconds,
        )
        repository().record(Store.PLAY_STORE, edge, PurchaseStatus.REFUNDED, t - PendingEndingRepository.RETENTION)
        repository().record(Store.PLAY_STORE, recent, PurchaseStatus.REFUNDED, t - 1.days)
        repository().record(Store.PLAY_STORE, unique(), PurchaseStatus.REFUNDED, t)
        repository().find(Store.PLAY_STORE, old, t) shouldBe null
        repository().find(Store.PLAY_STORE, edge, t) shouldBe PurchaseStatus.REFUNDED
        repository().find(Store.PLAY_STORE, recent, t) shouldBe PurchaseStatus.REFUNDED
    }

    "an entry nobody purged is still not found once it is older than the retention" {
        val token = unique()
        repository().record(Store.PLAY_STORE, token, PurchaseStatus.REVOKED, NOW)
        repository().find(Store.PLAY_STORE, token, NOW + PendingEndingRepository.RETENTION) shouldBe
            PurchaseStatus.REVOKED
        repository().find(Store.PLAY_STORE, token, NOW + PendingEndingRepository.RETENTION + 1.seconds) shouldBe null
    }
}
