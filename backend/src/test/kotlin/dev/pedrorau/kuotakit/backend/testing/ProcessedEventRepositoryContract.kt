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
import dev.pedrorau.kuotakit.backend.domain.ProcessedEventRepository
import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private fun unique() = "msg-${UUID.randomUUID()}"

/** What every [ProcessedEventRepository] must do. The in-memory fake and the Postgres implementation both run it. */
fun StringSpec.processedEventRepositoryContract(repository: () -> ProcessedEventRepository) {
    "a message nobody recorded has not been seen" {
        repository().seen(Store.PLAY_STORE, unique()) shouldBe false
    }

    "a recorded message is seen, and only for its own store" {
        val id = unique()
        repository().record(Store.PLAY_STORE, id, NOW)
        repository().seen(Store.PLAY_STORE, id) shouldBe true
        repository().seen(Store.APP_STORE, id) shouldBe false
        repository().seen(Store.PLAY_STORE, unique()) shouldBe false
    }

    "recording the same message twice is harmless" {
        val id = unique()
        repository().record(Store.PLAY_STORE, id, NOW)
        repository().record(Store.PLAY_STORE, id, NOW + 1.seconds)
        repository().seen(Store.PLAY_STORE, id) shouldBe true
    }

    "a message is forgotten once it is older than the retention, and not before" {
        val old = unique()
        val recent = unique()
        val edge = unique()
        val t = NOW + 100.days
        repository().record(Store.PLAY_STORE, old, t - ProcessedEventRepository.RETENTION - 1.seconds)
        repository().record(Store.PLAY_STORE, edge, t - ProcessedEventRepository.RETENTION)
        repository().record(Store.PLAY_STORE, recent, t - 1.days)
        repository().record(Store.PLAY_STORE, unique(), t)
        repository().seen(Store.PLAY_STORE, old) shouldBe false
        repository().seen(Store.PLAY_STORE, edge) shouldBe true
        repository().seen(Store.PLAY_STORE, recent) shouldBe true
    }
}
