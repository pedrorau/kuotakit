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

import dev.pedrorau.kuotakit.backend.testing.FakePlayStoreApi
import dev.pedrorau.kuotakit.backend.testing.FixedClock
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.playSubscription
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ReceiptAfterRefundTest :
    StringSpec({
        "sending the receipt again after a refund or a revocation brings nothing back" {
            PurchaseStatus.entries.filter { it.isFinal() }.forEach { final ->
                val google = FakePlayStoreApi()
                val repository = InMemoryPurchaseRepository()
                val service =
                    ReceiptService(google, "com.acme.app", repository, FixedClock(), PendingEndingRepository.None)
                val ended = purchase(token = "token-1", status = final)
                repository.save("anon_1", ended)
                // Google still describes it as a paid, unacknowledged subscription
                google.subscription = { playSubscription(acknowledged = false) }

                service.submit("anon_1", PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "token-1"))

                repository.purchasesOf("anon_1") shouldBe listOf(ended)
                google.calls.none { it.startsWith("acknowledge") } shouldBe true
            }
        }
    })
