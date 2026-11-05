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

import dev.pedrorau.kuotakit.backend.domain.ProcessedEventRepository
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.time.Instant

/** A [ProcessedEventRepository] in memory, for tests of everything that is not storage. It passes the same contract. */
class InMemoryProcessedEvents : ProcessedEventRepository {
    private val mutex = Mutex()
    private val received = mutableMapOf<Pair<Store, String>, Instant>()

    override suspend fun seen(
        store: Store,
        messageId: String,
    ): Boolean {
        yield()
        return mutex.withLock { (store to messageId) in received }
    }

    override suspend fun record(
        store: Store,
        messageId: String,
        at: Instant,
    ) {
        yield()
        mutex.withLock {
            received.putIfAbsent(store to messageId, at)
            received.entries.removeAll { it.value < at - ProcessedEventRepository.RETENTION }
        }
    }
}
