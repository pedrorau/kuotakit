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
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Refunds and revocations that reached the backend before the purchase did (docs/adr/0016). A purchase that is
 * refunded before the app ever sends its receipt would otherwise be stored later as the active purchase Google may
 * still describe. Only a final status can wait here, and the first one is kept.
 */
interface PendingEndingRepository {
    /** Remembers that the purchase ended. Throws [IllegalArgumentException] for a status that is not final. */
    suspend fun record(
        store: Store,
        token: String,
        status: PurchaseStatus,
        at: Instant,
    )

    /** What is waiting for this purchase, ignoring what was recorded more than [RETENTION] before [now]. */
    suspend fun find(
        store: Store,
        token: String,
        now: Instant,
    ): PurchaseStatus?

    /** Forgets it once the purchase was stored in that final status. */
    suspend fun forget(
        store: Store,
        token: String,
    )

    companion object {
        /** Longer than a user is likely to stay without opening the app after paying and being refunded. */
        val RETENTION = 60.days

        /** Remembers nothing: for services that do not need it. */
        val None: PendingEndingRepository =
            object : PendingEndingRepository {
                override suspend fun record(
                    store: Store,
                    token: String,
                    status: PurchaseStatus,
                    at: Instant,
                ) = Unit

                override suspend fun find(
                    store: Store,
                    token: String,
                    now: Instant,
                ): PurchaseStatus? = null

                override suspend fun forget(
                    store: Store,
                    token: String,
                ) = Unit
            }
    }
}
