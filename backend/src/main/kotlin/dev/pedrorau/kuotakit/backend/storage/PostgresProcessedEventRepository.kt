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

package dev.pedrorau.kuotakit.backend.storage

import dev.pedrorau.kuotakit.backend.domain.ProcessedEventRepository
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Instant

/** [ProcessedEventRepository] on Postgres. */
internal class PostgresProcessedEventRepository(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProcessedEventRepository {
    override suspend fun seen(
        store: Store,
        messageId: String,
    ): Boolean =
        inTransaction {
            !StoreEvents
                .selectAll()
                .where { (StoreEvents.store eq store.wire()) and (StoreEvents.messageId eq messageId) }
                .empty()
        }

    override suspend fun record(
        store: Store,
        messageId: String,
        at: Instant,
    ) = inTransaction {
        StoreEvents.insertIgnore {
            it[StoreEvents.store] = store.wire()
            it[StoreEvents.messageId] = messageId
            it[receivedAt] = at
        }
        val oldest = at - ProcessedEventRepository.RETENTION
        StoreEvents.deleteWhere { receivedAt less oldest }
        Unit
    }

    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        withContext(dispatcher) { suspendTransaction(database) { block() } }
}
