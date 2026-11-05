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

package dev.pedrorau.kuotakit.backend

import dev.pedrorau.kuotakit.backend.domain.EntitlementsConfig
import dev.pedrorau.kuotakit.backend.domain.PlayNotificationService
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.domain.PushTokenVerifier
import dev.pedrorau.kuotakit.backend.domain.ReceiptService
import dev.pedrorau.kuotakit.backend.web.ApiKeys
import kotlin.time.Clock

/** Everything the HTTP layer needs, built by `main` (or by a test with fakes). */
@Suppress("LongParameterList") // a plain holder of what `main` wires: one field per thing the routes use
class Services(
    val entitlements: EntitlementsConfig,
    val apiKeys: ApiKeys,
    val purchases: PurchaseRepository,
    val receipts: ReceiptService,
    val notifications: PlayNotificationService,
    /** `null` when the webhook is not configured: it then refuses every push, because it cannot verify any. */
    val pushVerifier: PushTokenVerifier?,
    val clock: Clock = Clock.System,
)
