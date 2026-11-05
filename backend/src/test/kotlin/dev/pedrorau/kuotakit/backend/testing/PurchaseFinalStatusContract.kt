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
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.backend.domain.TokenAlreadyLinkedException
import dev.pedrorau.kuotakit.backend.domain.purchase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.days

private fun unique(prefix: String) = "$prefix-${UUID.randomUUID()}"

/**
 * A refund or a revocation is the end of a purchase: whatever is written afterwards, even by a slow update that
 * read the purchase before the refund, must not bring it back (docs/adr/0014). Both repositories run it.
 */
fun StringSpec.purchaseFinalStatusContract(repository: () -> PurchaseRepository) {
    "a purchase that ended for good is not changed by a later write from its owner" {
        PurchaseStatus.entries.filter { it.isFinal() }.forEach { final ->
            val user = unique("user")
            val token = unique("token")
            val ended = purchase(token = token, status = final, expiresAt = NOW + 10.days)
            repository().save(user, purchase(token = token, status = PurchaseStatus.ACTIVE))
            repository().save(user, ended)
            repository().save(user, purchase(token = token, status = PurchaseStatus.ACTIVE, expiresAt = NOW + 90.days))
            repository().purchasesOf(user) shouldBe listOf(ended)
        }
    }

    "the first reason a purchase ended for good is kept" {
        val user = unique("user")
        val token = unique("token")
        repository().save(user, purchase(token = token, status = PurchaseStatus.ACTIVE))
        repository().save(user, purchase(token = token, status = PurchaseStatus.REFUNDED))
        repository().save(user, purchase(token = token, status = PurchaseStatus.REVOKED))
        repository().purchasesOf(user).single().status shouldBe PurchaseStatus.REFUNDED
    }

    "any other status can still change, and can end for good" {
        val user = unique("user")
        val token = unique("token")
        listOf(
            PurchaseStatus.ACTIVE,
            PurchaseStatus.ON_HOLD,
            PurchaseStatus.PAUSED,
            PurchaseStatus.EXPIRED,
            PurchaseStatus.ACTIVE,
        ).forEach { status ->
            repository().save(user, purchase(token = token, status = status))
            repository().purchasesOf(user).single().status shouldBe status
        }
        repository().save(user, purchase(token = token, status = PurchaseStatus.REVOKED))
        repository().purchasesOf(user).single().status shouldBe PurchaseStatus.REVOKED
    }

    "another user still cannot take the token of a purchase that ended, and is told so" {
        val owner = unique("owner")
        val thief = unique("thief")
        val token = unique("token")
        repository().save(owner, purchase(token = token, status = PurchaseStatus.REFUNDED))
        shouldThrow<TokenAlreadyLinkedException> { repository().save(thief, purchase(token = token)) }
        repository().purchasesOf(thief) shouldBe emptyList()
        repository().purchasesOf(owner).single().status shouldBe PurchaseStatus.REFUNDED
    }
}
