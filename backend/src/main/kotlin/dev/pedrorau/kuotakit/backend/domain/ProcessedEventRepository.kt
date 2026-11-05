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
 * The store notifications already handled, so a push that Pub/Sub delivers twice does not cost a second call to the
 * store (ADR 0014). Handling is idempotent anyway, so this only saves quota; losing it never corrupts anything.
 */
interface ProcessedEventRepository {
    suspend fun seen(
        store: Store,
        messageId: String,
    ): Boolean

    /** Remembers the message. It also forgets those older than [RETENTION], so the table cannot grow forever. */
    suspend fun record(
        store: Store,
        messageId: String,
        at: Instant,
    )

    companion object {
        /** Twice the longest time Pub/Sub keeps an unacknowledged message (seven days). */
        val RETENTION = 14.days
    }
}

/** Checks the proof that a push really comes from the Pub/Sub subscription of this project. */
interface PushTokenVerifier {
    /**
     * True only for a valid token from the expected service account and audience. A token that is not valid is
     * `false`; if Google's keys cannot be fetched it throws [StoreUnavailableException], so Pub/Sub retries.
     */
    suspend fun verify(bearerToken: String): Boolean
}
