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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class EntitlementsConfigTest :
    StringSpec({
        val config =
            CONFIG.copy(
                oneTimeProducts =
                    mapOf(
                        Store.PLAY_STORE to setOf("lifetime_unlock", "pack"),
                        Store.APP_STORE to setOf("com.acme.lifetime"),
                    ),
            )

        "a product is bought once only in the store and under the id that was configured" {
            config.isOneTime(Store.PLAY_STORE, "lifetime_unlock") shouldBe true
            config.isOneTime(Store.PLAY_STORE, "pack") shouldBe true
            config.isOneTime(Store.APP_STORE, "com.acme.lifetime") shouldBe true
            config.isOneTime(Store.APP_STORE, "lifetime_unlock") shouldBe false
            config.isOneTime(Store.PLAY_STORE, "com.acme.lifetime") shouldBe false
            config.isOneTime(Store.PLAY_STORE, "premium_monthly") shouldBe false
            config.isOneTime(Store.PLAY_STORE, "") shouldBe false
        }

        "with no one-time products every product is a subscription" {
            CONFIG.oneTimeProducts shouldBe emptyMap()
            CONFIG.isOneTime(Store.PLAY_STORE, "premium_monthly") shouldBe false
        }
    })
