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

import dev.pedrorau.kuotakit.backend.domain.PendingEndingRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.time.Instant

/** A [PendingEndingRepository] in memory, for tests of everything that is not storage. It passes the same contract. */
class InMemoryPendingEndings : PendingEndingRepository {
    private class Entry(
        val status: PurchaseStatus,
        val at: Instant,
    )

    private val mutex = Mutex()
    private val entries = mutableMapOf<Pair<Store, String>, Entry>()

    override suspend fun record(
        store: Store,
        token: String,
        status: PurchaseStatus,
        at: Instant,
    ) {
        require(status.isFinal()) { "Only a refund or a revocation can wait for a receipt, not $status" }
        yield()
        mutex.withLock {
            entries.putIfAbsent(store to token, Entry(status, at))
            entries.entries.removeAll { it.value.at < at - PendingEndingRepository.RETENTION }
        }
    }

    override suspend fun find(
        store: Store,
        token: String,
        now: Instant,
    ): PurchaseStatus? {
        yield()
        return mutex.withLock {
            entries[store to token]
                ?.takeIf { it.at >= now - PendingEndingRepository.RETENTION }
                ?.status
        }
    }

    override suspend fun forget(
        store: Store,
        token: String,
    ) {
        yield()
        mutex.withLock { entries.remove(store to token) }
    }
}
