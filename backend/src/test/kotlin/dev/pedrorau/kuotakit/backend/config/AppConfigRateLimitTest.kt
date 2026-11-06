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

private val MINIMAL =
    mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db:5432/kuotakit",
        "DATABASE_USER" to "kuotakit",
        "DATABASE_PASSWORD" to "secret",
        "KUOTAKIT_APP_KEYS" to "pk_0123456789abcdef",
    )

private fun problems(env: Map<String, String>) =
    shouldThrow<ConfigException> {
        AppConfig.fromEnv(env)
    }.message.orEmpty()

class AppConfigRateLimitTest :
    StringSpec({
        "the limits have defaults, and the forwarded address is not trusted unless asked" {
            val config = AppConfig.fromEnv(MINIMAL).rateLimit
            config shouldBe RateLimitConfig(300, 30, 10, false)
        }

        "every limit can be set" {
            val env =
                MINIMAL +
                    mapOf(
                        "KUOTAKIT_RATE_LIMIT_PER_MINUTE" to " 600 ",
                        "KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_MINUTE" to "60",
                        "KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_USER_PER_MINUTE" to "5",
                        "KUOTAKIT_TRUST_FORWARDED_FOR" to "true",
                    )
            AppConfig.fromEnv(env).rateLimit shouldBe RateLimitConfig(600, 60, 5, true)
        }

        "an empty value is the default" {
            val env = MINIMAL + mapOf("KUOTAKIT_RATE_LIMIT_PER_MINUTE" to "", "KUOTAKIT_TRUST_FORWARDED_FOR" to " ")
            AppConfig.fromEnv(env).rateLimit shouldBe RateLimitConfig()
        }

        "a limit that is not a whole number of at least 1 is reported, with its name" {
            listOf("0", "-5", "ten", "1.5").forEach { bad ->
                problems(MINIMAL + mapOf("KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_MINUTE" to bad)) shouldContain
                    "KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_MINUTE"
            }
        }

        "the proxy setting must be true or false" {
            listOf("yes", "1", "TRUE").forEach { bad ->
                problems(MINIMAL + mapOf("KUOTAKIT_TRUST_FORWARDED_FOR" to bad)) shouldContain
                    "KUOTAKIT_TRUST_FORWARDED_FOR"
            }
        }

        "every problem is reported at once" {
            val message =
                problems(
                    MINIMAL +
                        mapOf(
                            "KUOTAKIT_RATE_LIMIT_PER_MINUTE" to "0",
                            "KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_USER_PER_MINUTE" to "x",
                            "KUOTAKIT_TRUST_FORWARDED_FOR" to "maybe",
                        ),
                )
            message shouldContain "KUOTAKIT_RATE_LIMIT_PER_MINUTE"
            message shouldContain "KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_USER_PER_MINUTE"
            message shouldContain "KUOTAKIT_TRUST_FORWARDED_FOR"
        }
    })
