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

import dev.pedrorau.kuotakit.backend.domain.Purchase
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.domain.TokenAlreadyLinkedException
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * A [PurchaseRepository] in memory, for tests of everything that is not storage. It passes the same contract. Each
 * call yields first: real storage suspends, and a fake that never does would hide the code that runs after resuming.
 */
class InMemoryPurchaseRepository : PurchaseRepository {
    private val mutex = Mutex()
    private val rows = mutableMapOf<Pair<Store, String>, Pair<String, Purchase>>()

    override suspend fun purchasesOf(appUserId: String): List<Purchase> {
        yield()
        return mutex.withLock {
            rows.values
                .filter { (owner, _) -> owner == appUserId }
                .map { (_, purchase) -> purchase }
                .sortedWith(compareBy({ it.purchasedAt }, { it.token }))
        }
    }

    override suspend fun save(
        appUserId: String,
        purchase: Purchase,
    ) {
        yield()
        mutex.withLock {
            val key = purchase.store to purchase.token
            val owner = rows[key]?.first
            if (owner != null && owner != appUserId) throw TokenAlreadyLinkedException(purchase.store, purchase.token)
            if (rows[key]?.second?.status?.isFinal() != true) rows[key] = appUserId to purchase
        }
    }

    override suspend fun ownerOf(
        store: Store,
        token: String,
    ): String? {
        yield()
        return mutex.withLock { rows[store to token]?.first }
    }
}
