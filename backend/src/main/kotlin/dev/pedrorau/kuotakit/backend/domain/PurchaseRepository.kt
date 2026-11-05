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

import dev.pedrorau.kuotakit.contracts.Store

/**
 * Where purchases are kept. The domain depends only on this interface; the Postgres implementation lives in the
 * `storage` package and every implementation must pass `PurchaseRepositoryContract` (ADR 0009).
 */
interface PurchaseRepository {
    /**
     * The purchases of a user, oldest first; empty for a user nobody has ever heard of. Instants are kept at
     * microsecond precision (Postgres), which is far finer than anything a store reports.
     */
    suspend fun purchasesOf(appUserId: String): List<Purchase>

    /**
     * Creates the purchase, or replaces the one with the same store and token. Throws [TokenAlreadyLinkedException]
     * when that token already belongs to another user.
     */
    suspend fun save(
        appUserId: String,
        purchase: Purchase,
    )

    /** The user a purchase token belongs to, if any. */
    suspend fun ownerOf(
        store: Store,
        token: String,
    ): String?
}

/** The purchase token is already linked to another user (HTTP 409, `token_already_linked`). */
class TokenAlreadyLinkedException(
    val store: Store,
    val token: String,
) : RuntimeException("The $store purchase token is already linked to another user")
