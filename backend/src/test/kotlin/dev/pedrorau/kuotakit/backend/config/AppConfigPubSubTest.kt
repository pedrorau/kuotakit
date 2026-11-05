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

private val WITH_GOOGLE =
    mapOf(
        "DATABASE_URL" to "jdbc:postgresql://db:5432/kuotakit",
        "DATABASE_USER" to "kuotakit",
        "DATABASE_PASSWORD" to "secret",
        "KUOTAKIT_APP_KEYS" to "pk_0123456789abcdef",
        "KUOTAKIT_GOOGLE_PACKAGE_NAME" to "com.acme.app",
        "KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE" to "/run/secrets/play.json",
    )

private const val AUDIENCE = "KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE"
private const val EMAIL = "KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL"

private fun problems(env: Map<String, String>) =
    shouldThrow<ConfigException> {
        AppConfig.fromEnv(env)
    }.message.orEmpty()

class AppConfigPubSubTest :
    StringSpec({
        "the webhook is optional: without the proof the backend still starts" {
            AppConfig.fromEnv(WITH_GOOGLE).pubSub shouldBe null
            AppConfig.fromEnv(WITH_GOOGLE + mapOf(AUDIENCE to " ", EMAIL to "")).pubSub shouldBe null
        }

        "the audience and the service account configure it" {
            val env =
                WITH_GOOGLE +
                    mapOf(
                        AUDIENCE to " https://kuota.example.com/v1/webhooks/google ",
                        EMAIL to " push@p.iam.gserviceaccount.com ",
                    )
            AppConfig.fromEnv(env).pubSub shouldBe
                PubSubConfig("https://kuota.example.com/v1/webhooks/google", "push@p.iam.gserviceaccount.com")
        }

        "half of it is an error, not a silently disabled webhook" {
            problems(WITH_GOOGLE + mapOf(AUDIENCE to "https://x")) shouldContain EMAIL
            problems(WITH_GOOGLE + mapOf(EMAIL to "push@p.iam.gserviceaccount.com")) shouldContain AUDIENCE
        }

        "the account must be an email address" {
            problems(WITH_GOOGLE + mapOf(AUDIENCE to "https://x", EMAIL to "not-an-email")) shouldContain
                "email address"
        }

        "it needs Google Play configured, because the webhook re-reads purchases from Google" {
            val withoutGoogle = WITH_GOOGLE - "KUOTAKIT_GOOGLE_PACKAGE_NAME" - "KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE"
            problems(
                withoutGoogle + mapOf(AUDIENCE to "https://x", EMAIL to "push@p.iam.gserviceaccount.com"),
            ) shouldContain
                "KUOTAKIT_GOOGLE_PACKAGE_NAME"
        }
    })
