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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class PurchaseTest :
    StringSpec({
        "an active purchase grants access up to, but not including, its end" {
            val active = purchase(status = PurchaseStatus.ACTIVE, expiresAt = NOW + 1.seconds)
            active.grantsAccessAt(NOW) shouldBe true
            active.grantsAccessAt(NOW + 1.seconds) shouldBe false
            purchase(status = PurchaseStatus.ACTIVE, expiresAt = NOW).grantsAccessAt(NOW) shouldBe false
        }

        "an active purchase that never expires always grants access" {
            purchase(status = PurchaseStatus.ACTIVE, expiresAt = null).grantsAccessAt(NOW + 1000000.seconds) shouldBe
                true
        }

        "no other status grants access, whatever its end date says" {
            PurchaseStatus.entries.filter { it != PurchaseStatus.ACTIVE }.forEach { status ->
                purchase(status = status, expiresAt = NOW + 1000000.seconds).grantsAccessAt(NOW) shouldBe false
                purchase(status = status, expiresAt = null).grantsAccessAt(NOW) shouldBe false
            }
        }

        "the renewal flag is what was given" {
            purchase(autoRenewing = true).autoRenewing shouldBe true
            purchase(autoRenewing = false).autoRenewing shouldBe false
        }
    })
