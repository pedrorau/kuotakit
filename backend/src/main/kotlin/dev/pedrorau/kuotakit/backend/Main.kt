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
import dev.pedrorau.kuotakit.backend.config.EntitlementsYaml
import dev.pedrorau.kuotakit.backend.domain.ReceiptService
import dev.pedrorau.kuotakit.backend.google.GooglePlayStoreApi
import dev.pedrorau.kuotakit.backend.storage.PostgresStorage
import dev.pedrorau.kuotakit.backend.web.ApiKeys
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.IOException
import java.nio.file.Files
import kotlin.system.exitProcess
import kotlin.time.Clock

fun main() {
    val (config, entitlements) =
        try {
            val config = AppConfig.fromEnv(System.getenv())
            config to EntitlementsYaml.load(config.entitlementsFile)
        } catch (e: ConfigException) {
            // A wrong configuration is not a crash: say what is wrong, once, and stop.
            System.err.println(e.message)
            exitProcess(1)
        }

    PostgresStorage.open(config.database).use { storage ->
        val playStore =
            config.google?.let { google ->
                try {
                    GooglePlayStoreApi.fromServiceAccount(
                        Files.newInputStream(google.serviceAccountFile),
                        google.packageName,
                    )
                } catch (e: IOException) {
                    System.err.println(
                        "Cannot read the Google service account ${google.serviceAccountFile}: ${e.message}",
                    )
                    exitProcess(1)
                }
            }
        val receipts = ReceiptService(playStore, config.google?.packageName, storage.purchases, Clock.System)
        val services = Services(entitlements, ApiKeys(config.appKeys, config.secretKeys), storage.purchases, receipts)
        embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(services) }.start(wait = true)
    }
}
