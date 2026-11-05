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
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

private const val APP_KEY = "pk_0123456789abcdef"
private const val SECRET_KEY = "sk_0123456789abcdef"

private val MINIMAL =
    mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db:5432/kuotakit",
        "DATABASE_USER" to "kuotakit",
        "DATABASE_PASSWORD" to "secret",
        "KUOTAKIT_APP_KEYS" to APP_KEY,
    )

private fun problems(env: Map<String, String>): String =
    shouldThrow<ConfigException> {
        AppConfig.fromEnv(env)
    }.message.orEmpty()

class AppConfigTest :
    StringSpec({
        "the minimal environment works and the defaults apply" {
            val config = AppConfig.fromEnv(MINIMAL)
            config.port shouldBe 8080
            config.database shouldBe DatabaseConfig("jdbc:postgresql://db:5432/kuotakit", "kuotakit", "secret")
            config.appKeys shouldBe listOf(APP_KEY)
            config.secretKeys shouldBe emptyList()
            config.entitlementsFile shouldBe Path.of("/config/entitlements.yaml")
        }

        "everything can be set" {
            val config =
                AppConfig.fromEnv(
                    MINIMAL +
                        mapOf(
                            "PORT" to "9090",
                            "KUOTAKIT_SECRET_KEYS" to " $SECRET_KEY , sk_ffffffffffffffff ",
                            "KUOTAKIT_APP_KEYS" to "$APP_KEY,pk_aaaaaaaaaaaaaaaa",
                            "KUOTAKIT_ENTITLEMENTS_FILE" to "/etc/kuotakit/e.yaml",
                        ),
                )
            config.port shouldBe 9090
            config.appKeys shouldBe listOf(APP_KEY, "pk_aaaaaaaaaaaaaaaa")
            config.secretKeys shouldBe listOf(SECRET_KEY, "sk_ffffffffffffffff")
            config.entitlementsFile shouldBe Path.of("/etc/kuotakit/e.yaml")
        }

        "every missing variable is reported at once" {
            val message = problems(emptyMap())
            message shouldContain "DATABASE_URL is required"
            message shouldContain "DATABASE_USER is required"
            message shouldContain "DATABASE_PASSWORD is required"
            message shouldContain "set KUOTAKIT_APP_KEYS"
        }

        "a blank value counts as missing" {
            problems(MINIMAL + ("DATABASE_PASSWORD" to "  ")) shouldContain "DATABASE_PASSWORD is required"
        }

        "keys must have the right prefix and be long enough to guess" {
            problems(MINIMAL + ("KUOTAKIT_APP_KEYS" to "sk_0123456789abcdef")) shouldContain
                "KUOTAKIT_APP_KEYS: every key must start with pk_"
            problems(MINIMAL + ("KUOTAKIT_SECRET_KEYS" to "pk_0123456789abcdef")) shouldContain
                "KUOTAKIT_SECRET_KEYS: every key must start with sk_"
            problems(MINIMAL + ("KUOTAKIT_APP_KEYS" to "pk_short")) shouldContain
                "a key must have at least 16 characters"
        }

        "a key that could never travel in an Authorization header is rejected" {
            problems(MINIMAL + ("KUOTAKIT_SECRET_KEYS" to "sk_with space_0123456789")) shouldContain
                "may only have letters"
            problems(MINIMAL + ("KUOTAKIT_SECRET_KEYS" to "sk_bang!_0123456789ab")) shouldContain
                "may only have letters"
            problems(MINIMAL + ("KUOTAKIT_SECRET_KEYS" to "sk_0123456789abcd=x")) shouldContain "may only have letters"
            AppConfig
                .fromEnv(
                    MINIMAL + ("KUOTAKIT_SECRET_KEYS" to "sk_a.b_c~d+e/f-0123456789=="),
                ).secretKeys.size shouldBe
                1
        }

        "the placeholder keys of .env.example are rejected, so nobody runs with a publicly known key" {
            problems(MINIMAL + ("KUOTAKIT_APP_KEYS" to "pk_change_me_to_a_long_random_value")) shouldContain
                "replace the placeholder"
        }

        "printing the configuration never shows the password or a key" {
            val text =
                AppConfig
                    .fromEnv(
                        MINIMAL + mapOf("KUOTAKIT_SECRET_KEYS" to SECRET_KEY, "DATABASE_PASSWORD" to "hunter2-db"),
                    ).toString()
            text shouldNotContain "hunter2"
            text shouldNotContain APP_KEY
            text shouldNotContain SECRET_KEY
            text shouldContain "password=***"
            DatabaseConfig("u", "n", "hunter2").toString() shouldNotContain "hunter2"
        }

        "a secret key alone is enough to start" {
            val config = AppConfig.fromEnv(MINIMAL - "KUOTAKIT_APP_KEYS" + ("KUOTAKIT_SECRET_KEYS" to SECRET_KEY))
            config.secretKeys shouldBe listOf(SECRET_KEY)
        }

        "the port must be a valid number" {
            problems(MINIMAL + ("PORT" to "abc")) shouldContain "PORT must be a number between 1 and 65535, not 'abc'"
            problems(MINIMAL + ("PORT" to "0")) shouldContain "PORT must be a number"
            problems(MINIMAL + ("PORT" to "65536")) shouldContain "PORT must be a number"
            AppConfig.fromEnv(MINIMAL + ("PORT" to "65535")).port shouldBe 65535
        }

        "a blank entitlements path falls back to the default" {
            AppConfig.fromEnv(MINIMAL + ("KUOTAKIT_ENTITLEMENTS_FILE" to "")).entitlementsFile shouldBe
                Path.of("/config/entitlements.yaml")
        }
    })
