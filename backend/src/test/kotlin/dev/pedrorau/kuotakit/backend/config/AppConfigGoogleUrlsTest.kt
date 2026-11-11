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
        "KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE" to "https://x/hook",
        "KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL" to "push@p.iam.gserviceaccount.com",
    )

private const val API = "KUOTAKIT_GOOGLE_API_BASE_URL"
private const val CERTS = "KUOTAKIT_GOOGLE_CERTS_URL"
private const val LOCK = "KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS"

private fun problems(env: Map<String, String>) =
    shouldThrow<ConfigException> {
        AppConfig.fromEnv(env)
    }.message.orEmpty()

/** Another address for Google is for the conformance suite only, and takes an explicit lock (ADR 0018). */
class AppConfigGoogleUrlsTest :
    StringSpec({
        "without the settings Google's own addresses are used" {
            val config = AppConfig.fromEnv(WITH_GOOGLE)
            config.google!!.apiBaseUrl shouldBe null
            config.pubSub!!.certsUrl shouldBe null
        }

        "with the lock the addresses are read" {
            val env =
                WITH_GOOGLE +
                    mapOf(API to " http://fake-google:9000/ ", CERTS to "http://fake-google:9000/certs", LOCK to "true")
            val config = AppConfig.fromEnv(env)
            config.google!!.apiBaseUrl shouldBe "http://fake-google:9000/"
            config.pubSub!!.certsUrl shouldBe "http://fake-google:9000/certs"
        }

        "an address without the lock stops the start-up and says why, naming the variables" {
            listOf(API, CERTS).forEach { name ->
                listOf(emptyMap(), mapOf(LOCK to "false"), mapOf(LOCK to " ")).forEach { lock ->
                    val message = problems(WITH_GOOGLE + (name to "http://fake-google:9000/") + lock)
                    message shouldContain name
                    message shouldContain "$LOCK=true"
                }
            }
        }

        "the lock must be true or false" {
            problems(WITH_GOOGLE + mapOf(API to "http://fake:9/", LOCK to "yes")) shouldContain LOCK
        }

        "the lock must be exactly true or false even when no address is set, and spaces around it are ignored" {
            listOf("yes", "True", "TRUE", "1", "on").forEach { bad ->
                problems(WITH_GOOGLE + (LOCK to bad)) shouldContain "$LOCK must be true or false"
            }
            AppConfig.fromEnv(WITH_GOOGLE + (LOCK to " true ")).google!!.apiBaseUrl shouldBe null
            AppConfig.fromEnv(WITH_GOOGLE + (LOCK to "false")).google!!.apiBaseUrl shouldBe null
        }

        "an address whose host has an underscore, as a compose service name may, is accepted" {
            val env =
                WITH_GOOGLE +
                    mapOf(API to "http://fake_google:9000/", CERTS to "http://fake_google:9000/c", LOCK to "true")
            AppConfig.fromEnv(env).google!!.apiBaseUrl shouldBe "http://fake_google:9000/"
        }

        "an address that is not http or https with a host is reported with its variable" {
            val notAddresses =
                listOf("ftp://fake-google/", "fake-google:9000", "http://", "not an address", "https:///x")
            notAddresses.forEach { bad ->
                problems(WITH_GOOGLE + mapOf(API to bad, LOCK to "true")) shouldContain
                    "$API must be an http or https address"
                problems(WITH_GOOGLE + mapOf(CERTS to bad, LOCK to "true")) shouldContain
                    "$CERTS must be an http or https address"
            }
        }

        "an address with no Google settings to use it is an error, not silence" {
            val noGoogle = WITH_GOOGLE - "KUOTAKIT_GOOGLE_PACKAGE_NAME" - "KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE"
            problems(noGoogle + mapOf(API to "http://fake:9/", LOCK to "true")) shouldContain
                "$API needs the Google Play settings"
            val noPubSub =
                WITH_GOOGLE - "KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE" - "KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL"
            problems(noPubSub + mapOf(CERTS to "http://fake:9/", LOCK to "true")) shouldContain
                "$CERTS needs the Pub/Sub settings"
        }

        "the lock alone changes nothing" {
            AppConfig.fromEnv(WITH_GOOGLE + (LOCK to "true")).google!!.apiBaseUrl shouldBe null
            AppConfig.fromEnv(WITH_GOOGLE + (LOCK to "true")).pubSub!!.certsUrl shouldBe null
        }
    })
