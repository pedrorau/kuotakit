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

import dev.pedrorau.kuotakit.backend.config.AppConfig
import dev.pedrorau.kuotakit.backend.config.ConfigException
import dev.pedrorau.kuotakit.backend.domain.EntitlementsConfig
import dev.pedrorau.kuotakit.backend.domain.PendingEndingRepository
import dev.pedrorau.kuotakit.backend.domain.PlayNotificationService
import dev.pedrorau.kuotakit.backend.domain.ProcessedEventRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import dev.pedrorau.kuotakit.backend.domain.ReceiptService
import dev.pedrorau.kuotakit.backend.google.GooglePlayStoreApi
import dev.pedrorau.kuotakit.backend.google.GooglePushTokenVerifier
import dev.pedrorau.kuotakit.backend.web.ApiKeys
import dev.pedrorau.kuotakit.contracts.Store
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Clock

/**
 * Builds everything the routes use from the configuration and the storage. It is a function of its own, and not part
 * of `main`, so that a test can start the backend's wiring with a configuration of its own. Throws [ConfigException]
 * when the service account key cannot be read.
 */
@Suppress("LongParameterList") // what `main` has at hand: the configuration, the storage and the clock
internal fun buildServices(
    config: AppConfig,
    entitlements: EntitlementsConfig,
    purchases: PurchaseRepository,
    events: ProcessedEventRepository,
    pendingEndings: PendingEndingRepository,
    clock: Clock = Clock.System,
): Services {
    val google = config.google
    val playStore =
        google?.let {
            try {
                GooglePlayStoreApi.fromServiceAccount(
                    Files.newInputStream(it.serviceAccountFile),
                    it.packageName,
                    it.apiBaseUrl,
                )
            } catch (e: IOException) {
                throw ConfigException(
                    "Cannot read the Google service account ${it.serviceAccountFile}: ${e.message}",
                    e,
                )
            }
        }
    val pubSub = config.pubSub
    if (google?.apiBaseUrl != null || pubSub?.certsUrl != null) {
        LoggerFactory
            .getLogger("kuotakit.wiring")
            .warn("Google is reached at another address, which is for testing only: do not use this in production")
    }
    val receipts =
        ReceiptService(playStore, google?.packageName, purchases, clock, pendingEndings) {
            entitlements.isOneTime(Store.PLAY_STORE, it)
        }
    val notifications =
        PlayNotificationService(playStore, google?.packageName, purchases, events, clock, pendingEndings)
    val pushVerifier = pubSub?.let { GooglePushTokenVerifier.create(it.audience, it.serviceAccountEmail, it.certsUrl) }
    return Services(
        entitlements,
        ApiKeys(config.appKeys, config.secretKeys),
        purchases,
        receipts,
        notifications,
        pushVerifier,
        clock,
        config.rateLimit,
    )
}
