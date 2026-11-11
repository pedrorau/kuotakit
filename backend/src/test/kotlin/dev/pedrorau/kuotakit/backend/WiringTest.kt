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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.pedrorau.kuotakit.backend.config.AppConfig
import dev.pedrorau.kuotakit.backend.config.ConfigException
import dev.pedrorau.kuotakit.backend.domain.CONFIG
import dev.pedrorau.kuotakit.backend.testing.FixedClock
import dev.pedrorau.kuotakit.backend.testing.InMemoryPendingEndings
import dev.pedrorau.kuotakit.backend.testing.InMemoryProcessedEvents
import dev.pedrorau.kuotakit.backend.testing.InMemoryPurchaseRepository
import dev.pedrorau.kuotakit.backend.testing.LocalGoogle
import dev.pedrorau.kuotakit.backend.testing.TestKey
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.nio.file.Path

private const val BASE = "/androidpublisher/v3/applications/com.acme.app/purchases"
private const val AUDIENCE = "https://kuotakit.example.com/v1/webhooks/google"
private const val ACCOUNT = "push@my-project.iam.gserviceaccount.com"

private fun env(
    keyFile: Path,
    google: LocalGoogle,
) = mapOf(
    "DATABASE_URL" to "jdbc:postgresql://db:5432/kuotakit",
    "DATABASE_USER" to "kuotakit",
    "DATABASE_PASSWORD" to "secret",
    "KUOTAKIT_APP_KEYS" to "pk_0123456789abcdef",
    "KUOTAKIT_GOOGLE_PACKAGE_NAME" to "com.acme.app",
    "KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE" to keyFile.toString(),
    "KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE" to AUDIENCE,
    "KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL" to ACCOUNT,
    "KUOTAKIT_GOOGLE_API_BASE_URL" to google.baseUrl,
    "KUOTAKIT_GOOGLE_CERTS_URL" to google.baseUrl + "certs",
    "KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS" to "true",
)

private fun wire(config: AppConfig) =
    buildServices(
        config,
        CONFIG,
        InMemoryPurchaseRepository(),
        InMemoryProcessedEvents(),
        InMemoryPendingEndings(),
        FixedClock(),
    )

/** What `main` wires from the environment, started with a configuration of the test (untested before, ADR 0014). */
class WiringTest :
    StringSpec({
        "started with the settings and the lock, the backend asks the configured Google and trusts its signing keys" {
            LocalGoogle().use { google ->
                val trusted = TestKey.generate()
                google.answer("POST", "/token", """{"access_token":"t","expires_in":3600,"token_type":"Bearer"}""")
                google.answer("GET", "$BASE/subscriptionsv2/tokens/tok1", subscription())
                google.answer("GET", "/certs", buildJsonObject { put("kid1", trusted.pem) }.toString())
                val services = wire(AppConfig.fromEnv(env(google.serviceAccountKeyFile(), google)))

                services.receipts.submit("anon_1", PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "tok1"))
                val verified = services.pushVerifier!!.verify(trusted.pushToken(AUDIENCE, ACCOUNT))

                services.purchases
                    .purchasesOf("anon_1")
                    .single()
                    .productId shouldBe "premium_monthly"
                verified shouldBe true
                google.requests.filter { it.startsWith("GET /androidpublisher") } shouldBe
                    listOf("GET $BASE/subscriptionsv2/tokens/tok1")
                google.requests.contains("GET /certs") shouldBe true
            }
        }

        "the backend warns in its log when it is started with another address for Google, and only then" {
            LocalGoogle().use { google ->
                val warnings = captureWarnings { wire(AppConfig.fromEnv(env(google.serviceAccountKeyFile(), google))) }
                warnings.size shouldBe 1
                warnings.single() shouldContain "for testing only"

                val withoutAddresses =
                    env(google.serviceAccountKeyFile(), google) - "KUOTAKIT_GOOGLE_API_BASE_URL" -
                        "KUOTAKIT_GOOGLE_CERTS_URL"
                captureWarnings { wire(AppConfig.fromEnv(withoutAddresses)) } shouldBe emptyList()
            }
        }

        "a service account key that cannot be read stops the start-up with a message" {
            LocalGoogle().use { google ->
                val config = AppConfig.fromEnv(env(Path.of("/does/not/exist.json"), google))
                shouldThrow<ConfigException> { wire(config) }.message.orEmpty() shouldContain "/does/not/exist.json"
            }
        }
    })

/** The warnings that [block] writes through the logger of the wiring. */
private fun captureWarnings(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger("kuotakit.wiring") as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
}

private fun subscription() =
    """
    {"subscriptionState":"SUBSCRIPTION_STATE_ACTIVE","startTime":"2026-10-01T10:00:00Z",
     "acknowledgementState":"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
     "externalAccountIdentifiers":{"obfuscatedExternalAccountId":"${playObfuscatedAccountId("anon_1")}"},
     "lineItems":[{"productId":"premium_monthly","expiryTime":"2027-01-01T00:00:00Z",
       "autoRenewingPlan":{"autoRenewEnabled":true},"offerPhase":{"basePrice":{}}}]}
    """.trimIndent()
