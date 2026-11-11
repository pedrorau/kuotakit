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

package dev.pedrorau.kuotakit.backend.google

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.testing.http.MockHttpTransport
import dev.pedrorau.kuotakit.backend.testing.LocalGoogle
import dev.pedrorau.kuotakit.backend.testing.TestKey
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

private const val BASE = "/androidpublisher/v3/applications/com.acme.app/purchases"
private const val AUDIENCE = "https://kuotakit.example.com/v1/webhooks/google"
private const val ACCOUNT = "push@my-project.iam.gserviceaccount.com"

private const val TOKEN_ANSWER = """{"access_token":"test-access-token","expires_in":3600,"token_type":"Bearer"}"""

/** The conformance suite points the backend at a Google of its own (docs/adr/0018): every address must be honoured. */
class GoogleRedirectionTest :
    StringSpec({
        "every call of the Play Developer API goes to the configured address, and so does the token request" {
            LocalGoogle().use { google ->
                google.answer("POST", "/token", TOKEN_ANSWER)
                google.answer(
                    "GET",
                    "$BASE/subscriptionsv2/tokens/tok1",
                    """{"subscriptionState":"SUBSCRIPTION_STATE_ACTIVE"}""",
                )
                google.answer("POST", "$BASE/subscriptions/premium_monthly/tokens/tok1:acknowledge", "{}")
                google.answer(
                    "GET",
                    "$BASE/productsv2/tokens/tok2",
                    """{"purchaseStateContext":{"purchaseState":"PURCHASED"}}""",
                )
                google.answer("POST", "$BASE/products/lifetime/tokens/tok2:acknowledge", "{}")
                val api =
                    GooglePlayStoreApi.fromServiceAccount(
                        Files.newInputStream(google.serviceAccountKeyFile()),
                        "com.acme.app",
                        google.baseUrl,
                    )

                api.subscription("tok1")
                api.acknowledge("premium_monthly", "tok1")
                api.oneTimePurchase("tok2")
                api.acknowledgeOneTime("lifetime", "tok2")

                google.requests shouldBe
                    listOf(
                        "POST /token",
                        "GET $BASE/subscriptionsv2/tokens/tok1",
                        "POST $BASE/subscriptions/premium_monthly/tokens/tok1:acknowledge",
                        "GET $BASE/productsv2/tokens/tok2",
                        "POST $BASE/products/lifetime/tokens/tok2:acknowledge",
                    )
                // the four API calls carry the token that the local server issued
                google.authorizations.drop(1) shouldBe List(4) { "Bearer test-access-token" }
            }
        }

        "the token endpoint is the token_uri of the key file, with no setting of its own" {
            LocalGoogle().use { google ->
                google.answer("POST", "/token", TOKEN_ANSWER)
                google.answer("GET", "$BASE/subscriptionsv2/tokens/tok1", "{}")
                val api =
                    GooglePlayStoreApi.fromServiceAccount(
                        Files.newInputStream(google.serviceAccountKeyFile()),
                        "com.acme.app",
                        google.baseUrl,
                    )

                api.subscription("tok1")

                google.requests.first() shouldBe "POST /token"
                google.bodies.first() shouldContain "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
            }
        }

        "the signing keys of the push tokens are fetched from the configured address" {
            LocalGoogle().use { google ->
                val trusted = TestKey.generate()
                google.answer("GET", "/certs", buildJsonObject { put("kid1", trusted.pem) }.toString())
                val verifier = GooglePushTokenVerifier.create(AUDIENCE, ACCOUNT, google.baseUrl + "certs")

                verifier.verify(trusted.pushToken(AUDIENCE, ACCOUNT)) shouldBe true
                verifier.verify(TestKey.generate().pushToken(AUDIENCE, ACCOUNT)) shouldBe false

                google.requests.distinct() shouldBe listOf("GET /certs")
            }
        }

        "without the settings Google's own addresses are used" {
            val api = GooglePlayStoreApi.create(MockHttpTransport(), HttpRequestInitializer { }, "com.acme.app")
            api.rootUrl shouldBe "https://androidpublisher.googleapis.com/"
            GooglePushTokenVerifier.create(AUDIENCE, ACCOUNT).certsUrl shouldBe
                "https://www.googleapis.com/oauth2/v1/certs"
        }
    })
