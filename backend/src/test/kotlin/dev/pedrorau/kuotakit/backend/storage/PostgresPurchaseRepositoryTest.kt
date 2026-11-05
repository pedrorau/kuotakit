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

import dev.pedrorau.kuotakit.backend.testing.TestPostgres
import dev.pedrorau.kuotakit.backend.testing.purchaseRepositoryContract
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PostgresPurchaseRepositoryTest :
    StringSpec({
        val config = TestPostgres.newDatabase()
        val storage = PostgresStorage.open(config)
        afterSpec { storage.close() }

        purchaseRepositoryContract { storage.purchases }

        "the migrations are idempotent: opening an up-to-date database again changes nothing" {
            PostgresStorage.open(config).use { second ->
                second.purchases.ownerOf(dev.pedrorau.kuotakit.contracts.Store.PLAY_STORE, "none") shouldBe
                    null
            }
        }
    })
