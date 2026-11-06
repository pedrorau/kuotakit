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

import dev.pedrorau.kuotakit.backend.domain.PendingEndingRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.time.Instant

/** [PendingEndingRepository] on Postgres. */
internal class PostgresPendingEndingRepository(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PendingEndingRepository {
    override suspend fun record(
        store: Store,
        token: String,
        status: PurchaseStatus,
        at: Instant,
    ) {
        require(status.isFinal()) { "Only a refund or a revocation can wait for a receipt, not $status" }
        inTransaction {
            // The first reason is kept: a second notification for the same purchase changes nothing.
            PendingEndings.insertIgnore {
                it[PendingEndings.store] = store.wire()
                it[purchaseToken] = token
                it[PendingEndings.status] = status.name.lowercase()
                it[receivedAt] = at
            }
            val oldest = at - PendingEndingRepository.RETENTION
            PendingEndings.deleteWhere { receivedAt less oldest }
        }
    }

    override suspend fun find(
        store: Store,
        token: String,
        now: Instant,
    ): PurchaseStatus? =
        inTransaction {
            val oldest = now - PendingEndingRepository.RETENTION
            PendingEndings
                .selectAll()
                .where {
                    (PendingEndings.store eq store.wire()) and
                        (PendingEndings.purchaseToken eq token) and
                        (PendingEndings.receivedAt greaterEq oldest)
                }.singleOrNull()
                ?.let { PurchaseStatus.valueOf(it[PendingEndings.status].uppercase()) }
        }

    override suspend fun forget(
        store: Store,
        token: String,
    ) {
        inTransaction {
            PendingEndings.deleteWhere { (PendingEndings.store eq store.wire()) and (purchaseToken eq token) }
        }
    }

    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        withContext(dispatcher) { suspendTransaction(database) { block() } }
}
