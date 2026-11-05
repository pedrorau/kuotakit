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

import dev.pedrorau.kuotakit.backend.domain.Purchase
import dev.pedrorau.kuotakit.backend.domain.PurchaseKind
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseStatus
import dev.pedrorau.kuotakit.backend.domain.TokenAlreadyLinkedException
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Clock

private val FINAL_STATUSES = PurchaseStatus.entries.filter { it.isFinal() }.map { it.name.lowercase() }

/** [PurchaseRepository] on Postgres. Only this package knows about Exposed, Flyway and JDBC (ADR 0009). */
internal class PostgresPurchaseRepository(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PurchaseRepository {
    override suspend fun purchasesOf(appUserId: String): List<Purchase> =
        inTransaction {
            Purchases
                .selectAll()
                .where { Purchases.appUserId eq appUserId }
                .orderBy(Purchases.purchasedAt to SortOrder.ASC, Purchases.purchaseToken to SortOrder.ASC)
                .map { it.toPurchase() }
        }

    override suspend fun save(
        appUserId: String,
        purchase: Purchase,
    ) = inTransaction {
        Subscribers.insertIgnore {
            it[Subscribers.appUserId] = appUserId
            it[createdAt] = Clock.System.now()
        }
        // One statement decides who wins a race for a token: it updates only the row of the same user, and the
        // unique constraint on (store, token) guarantees a second user can never get a row.
        val written =
            Purchases.upsert(
                keys = arrayOf(Purchases.store, Purchases.purchaseToken),
                onUpdateExclude = listOf(Purchases.id, Purchases.appUserId, Purchases.store, Purchases.purchaseToken),
                // A refund or a revocation is the end of a purchase: nothing written later may bring it back. The check
                // is in the same statement as the write, so a slow update cannot undo a refund that happened meanwhile.
                where = { (Purchases.appUserId eq appUserId) and (Purchases.status notInList FINAL_STATUSES) },
            ) {
                it[Purchases.appUserId] = appUserId
                it[store] = purchase.store.wire()
                it[purchaseToken] = purchase.token
                it[productId] = purchase.productId
                it[kind] = purchase.kind.wire()
                it[status] = purchase.status.wire()
                it[purchasedAt] = purchase.purchasedAt
                it[expiresAt] = purchase.expiresAt
                it[autoRenewing] = purchase.autoRenewing
                it[periodType] = purchase.periodType.wire()
                it[billingIssueDetectedAt] = purchase.billingIssueDetectedAt
                it[updatedAt] = purchase.updatedAt
            }
        // Nothing written: either the token belongs to another user, or its owner's purchase had ended for good.
        if (written.insertedCount == 0 && ownerOfInTransaction(purchase.store, purchase.token) != appUserId) {
            throw TokenAlreadyLinkedException(purchase.store, purchase.token)
        }
    }

    override suspend fun ownerOf(
        store: Store,
        token: String,
    ): String? = inTransaction { ownerOfInTransaction(store, token) }

    private fun ownerOfInTransaction(
        store: Store,
        token: String,
    ): String? =
        Purchases
            .select(Purchases.appUserId)
            .where { (Purchases.store eq store.wire()) and (Purchases.purchaseToken eq token) }
            .singleOrNull()
            ?.get(Purchases.appUserId)

    private suspend fun <T> inTransaction(block: suspend () -> T): T =
        withContext(dispatcher) { suspendTransaction(database) { block() } }

    private fun ResultRow.toPurchase() =
        Purchase(
            store = storeOf(this[Purchases.store]),
            productId = this[Purchases.productId],
            token = this[Purchases.purchaseToken],
            kind = PurchaseKind.valueOf(this[Purchases.kind].uppercase()),
            status = PurchaseStatus.valueOf(this[Purchases.status].uppercase()),
            purchasedAt = this[Purchases.purchasedAt],
            expiresAt = this[Purchases.expiresAt],
            autoRenewing = this[Purchases.autoRenewing],
            periodType = periodTypeOf(this[Purchases.periodType]),
            billingIssueDetectedAt = this[Purchases.billingIssueDetectedAt],
            updatedAt = this[Purchases.updatedAt],
        )
}

// The database stores the same names the API uses on the wire.
internal fun Store.wire(): String = if (this == Store.PLAY_STORE) "play_store" else "app_store"

private fun storeOf(value: String): Store =
    Store.entries.firstOrNull { it != Store.UNKNOWN && it.wire() == value }
        ?: error("Unknown store in the database: $value")

private fun PurchaseKind.wire(): String = name.lowercase()

private fun PurchaseStatus.wire(): String = name.lowercase()

private fun PeriodType.wire(): String = name.lowercase()

private fun periodTypeOf(value: String): PeriodType = PeriodType.valueOf(value.uppercase())
