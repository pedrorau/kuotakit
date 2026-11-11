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

package dev.pedrorau.kuotakit.conformance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private const val SUBSCRIPTION =
    "/androidpublisher/v3/applications/com.example.conformance/purchases/subscriptionsv2/tokens/tok1"
private const val ACKNOWLEDGE =
    "/androidpublisher/v3/applications/com.example.conformance/purchases/subscriptions/premium_monthly/tokens/tok1:acknowledge"

private val key = SigningKey.generate()

private fun <T> withFake(block: (String, FakeGoogleControl, Http) -> T): T =
    FakeGoogle(0, key).use { fake ->
        val url = "http://127.0.0.1:${fake.port}"
        block(url, FakeGoogleControl(url), Http())
    }

private fun Http.token(url: String) =
    Json
        .parseToJsonElement(send("POST", "$url/token", body = "grant_type=jwt-bearer").body)
        .jsonObject
        .getValue("access_token")
        .jsonPrimitive.content

class FakeGoogleTest :
    StringSpec({
        "it issues a different access token each time, and answers its health check" {
            withFake { url, _, http ->
                http.send("GET", "$url/health").status shouldBe 200
                (http.token(url) == http.token(url)) shouldBe false
            }
        }

        "a Play call without an access token that it issued is a 401 in Google's error format" {
            withFake { url, _, http ->
                val noToken = http.send("GET", url + SUBSCRIPTION)
                val wrong = http.send("GET", url + SUBSCRIPTION, mapOf("Authorization" to "Bearer made-up"))
                listOf(noToken, wrong).forEach {
                    it.status shouldBe 401
                    Json
                        .parseToJsonElement(it.body)
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                        .getValue("status")
                        .jsonPrimitive.content shouldBe "UNAUTHENTICATED"
                }
            }
        }

        "a purchase nobody scripted is a 404 and an acknowledge succeeds" {
            withFake { url, _, http ->
                val auth = mapOf("Authorization" to "Bearer ${http.token(url)}")
                http.send("GET", url + SUBSCRIPTION, auth).status shouldBe 404
                http.send("POST", url + ACKNOWLEDGE, auth, "{}").status shouldBe 204
            }
        }

        "a scripted answer is given until the reset, for the method and the path it was scripted for" {
            withFake { url, control, http ->
                val auth = mapOf("Authorization" to "Bearer ${http.token(url)}")
                control.answer("GET", SUBSCRIPTION, 200, """{"subscriptionState":"X"}""")
                http.send("GET", url + SUBSCRIPTION, auth).body shouldContain "subscriptionState"
                http.send("GET", url + SUBSCRIPTION, auth).status shouldBe 200
                http.send("POST", url + SUBSCRIPTION, auth).status shouldBe 404
                control.answer("POST", ACKNOWLEDGE, 503, """{"error":{"code":503}}""")
                http.send("POST", url + ACKNOWLEDGE, auth, "{}").status shouldBe 503
                control.reset()
                http.send("GET", url + SUBSCRIPTION, auth).status shouldBe 404
            }
        }

        "a scripted answer wins over the checks, so a scenario can force a 401 or an outage of the signing keys" {
            withFake { url, control, http ->
                control.answer("GET", SUBSCRIPTION, 401, "{}")
                control.answer("GET", "/oauth2/v1/certs", 500, "")
                http
                    .send(
                        "GET",
                        url + SUBSCRIPTION,
                        mapOf("Authorization" to "Bearer ${http.token(url)}"),
                    ).status shouldBe
                    401
                http.send("GET", "$url/oauth2/v1/certs").status shouldBe 500
            }
        }

        "the log has every call the backend made, in order, and not the control calls" {
            withFake { url, control, http ->
                val auth = mapOf("Authorization" to "Bearer ${http.token(url)}")
                http.send("GET", url + SUBSCRIPTION, auth)
                http.send("POST", url + ACKNOWLEDGE, auth, "{}")
                control.calls() shouldBe listOf("POST /token", "GET $SUBSCRIPTION", "POST $ACKNOWLEDGE")
                control.reset()
                control.calls() shouldBe emptyList()
            }
        }

        "the signing keys are served in both formats and both describe the same key" {
            withFake { url, _, http ->
                val pem = Json.parseToJsonElement(http.send("GET", "$url/oauth2/v1/certs").body).jsonObject
                val certificate =
                    CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(
                            ByteArrayInputStream(
                                pem
                                    .getValue(key.keyId)
                                    .jsonPrimitive.content
                                    .toByteArray(),
                            ),
                        )
                val fromPem = certificate.publicKey as RSAPublicKey

                val jwk =
                    Json
                        .parseToJsonElement(http.send("GET", "$url/oauth2/v3/certs").body)
                        .jsonObject
                        .getValue("keys")
                        .jsonArray
                        .single()
                        .jsonObject
                jwk.getValue("kid").jsonPrimitive.content shouldBe key.keyId
                jwk.getValue("kty").jsonPrimitive.content shouldBe "RSA"

                fun number(name: String) =
                    BigInteger(1, Base64.getUrlDecoder().decode(jwk.getValue(name).jsonPrimitive.content))
                number("n") shouldBe fromPem.modulus
                number("e") shouldBe fromPem.publicExponent
            }
        }

        "a path that is nothing it knows is a 404, and a control call that is nothing it knows too" {
            withFake { url, _, http ->
                http.send("GET", "$url/nothing").status shouldBe 404
                http.send("POST", "$url/control/nothing").status shouldBe 404
            }
        }
    })
