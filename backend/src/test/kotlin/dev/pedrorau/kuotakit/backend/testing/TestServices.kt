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

import dev.pedrorau.kuotakit.backend.Services
import dev.pedrorau.kuotakit.backend.domain.CONFIG
import dev.pedrorau.kuotakit.backend.domain.NOW
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.web.ApiKeys
import kotlin.time.Clock
import kotlin.time.Instant

const val APP_KEY = "pk_test_0123456789abcdef"
const val SECRET_KEY = "sk_test_0123456789abcdef"

/** A clock that always says the same moment, so a response is the same every time. */
class FixedClock(
    private val instant: Instant = NOW,
) : Clock {
    override fun now(): Instant = instant
}

fun testServices(purchases: PurchaseRepository = InMemoryPurchaseRepository()) =
    Services(
        entitlements = CONFIG,
        apiKeys = ApiKeys(listOf(APP_KEY), listOf(SECRET_KEY)),
        purchases = purchases,
        clock = FixedClock(),
    )
