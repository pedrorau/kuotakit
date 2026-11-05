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

import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.TestPostgres
import dev.pedrorau.kuotakit.backend.testing.processedEventRepositoryContract
import dev.pedrorau.kuotakit.backend.testing.purchaseFinalStatusContract
import io.kotest.core.spec.style.StringSpec

class InMemoryProcessedEventsTest :
    StringSpec({
        val repository = InMemoryProcessedEvents()
        processedEventRepositoryContract { repository }
    })

class PostgresProcessedEventRepositoryTest :
    StringSpec({
        val storage = PostgresStorage.open(TestPostgres.newDatabase())
        afterSpec { storage.close() }
        processedEventRepositoryContract { storage.events }
    })

class InMemoryPurchaseFinalStatusTest :
    StringSpec({
        val repository = InMemoryPurchaseRepository()
        purchaseFinalStatusContract { repository }
    })

class PostgresPurchaseFinalStatusTest :
    StringSpec({
        val storage = PostgresStorage.open(TestPostgres.newDatabase())
        afterSpec { storage.close() }
        purchaseFinalStatusContract { storage.purchases }
    })
