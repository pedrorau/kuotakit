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

package dev.pedrorau.kuotakit.backend.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

private val MINIMAL =
    mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db:5432/kuotakit",
        "DATABASE_USER" to "kuotakit",
        "DATABASE_PASSWORD" to "secret",
        "KUOTAKIT_APP_KEYS" to "pk_0123456789abcdef",
    )

private const val PACKAGE = "KUOTAKIT_GOOGLE_PACKAGE_NAME"
private const val FILE = "KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE"

class AppConfigGoogleTest :
    StringSpec({
        "Google Play is optional: without it the backend still starts" {
            AppConfig.fromEnv(MINIMAL).google shouldBe null
            AppConfig.fromEnv(MINIMAL + mapOf(PACKAGE to " ", FILE to "")).google shouldBe null
        }

        "the package and the service account file configure it" {
            val config =
                AppConfig.fromEnv(MINIMAL + mapOf(PACKAGE to " com.acme.app ", FILE to "/run/secrets/play.json"))
            config.google shouldBe GoogleConfig("com.acme.app", Path.of("/run/secrets/play.json"))
        }

        "half a configuration is an error, not a silently disabled Google" {
            shouldThrow<ConfigException> { AppConfig.fromEnv(MINIMAL + mapOf(PACKAGE to "com.acme.app")) }
                .message
                .orEmpty() shouldContain FILE
            shouldThrow<ConfigException> { AppConfig.fromEnv(MINIMAL + mapOf(FILE to "/x.json")) }
                .message
                .orEmpty() shouldContain PACKAGE
        }

        "the configuration printed in a log shows the file name, which holds no secret" {
            AppConfig.fromEnv(MINIMAL + mapOf(PACKAGE to "com.acme.app", FILE to "/x.json")).toString() shouldContain
                "google="
        }
    })
