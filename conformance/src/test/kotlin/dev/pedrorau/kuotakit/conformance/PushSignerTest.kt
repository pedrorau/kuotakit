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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

private val key = SigningKey.generate("key-for-the-signer-test")
private const val NOW = 1_800_000_000L

private fun token(vararg fields: Pair<String, Any>): List<String> =
    PushSigner
        .token(
            key,
            buildJsonObject {
                put("audience", "http://backend/hook")
                put("email", "push@example.com")
                fields.forEach { (name, value) ->
                    when (value) {
                        is Boolean -> put(name, value)
                        is Number -> put(name, value)
                        else -> put(name, value.toString())
                    }
                }
            },
            NOW,
        ).split(".")

private fun part(text: String): JsonObject =
    Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(text))).jsonObject

private fun verifies(parts: List<String>): Boolean {
    val certificate =
        CertificateFactory
            .getInstance(
                "X.509",
            ).generateCertificate(ByteArrayInputStream(key.certificatePem.toByteArray()))
    return Signature
        .getInstance("SHA256withRSA")
        .apply {
            initVerify(certificate)
            update((parts[0] + "." + parts[1]).toByteArray())
        }.verify(Base64.getUrlDecoder().decode(parts[2]))
}

class PushSignerTest :
    StringSpec({
        "a token with no changes is a signed RS256 token for the audience and account, valid for an hour" {
            val parts = token()
            parts.size shouldBe 3
            part(parts[0]).getValue("alg").jsonPrimitive.content shouldBe "RS256"
            part(parts[0]).getValue("kid").jsonPrimitive.content shouldBe "key-for-the-signer-test"
            val payload = part(parts[1])
            payload.getValue("aud").jsonPrimitive.content shouldBe "http://backend/hook"
            payload.getValue("email").jsonPrimitive.content shouldBe "push@example.com"
            payload.getValue("email_verified").jsonPrimitive.boolean shouldBe true
            payload.getValue("iss").jsonPrimitive.content shouldBe "https://accounts.google.com"
            payload.getValue("exp").jsonPrimitive.long shouldBe NOW + 3600
            verifies(parts) shouldBe true
        }

        "each change makes the one thing wrong that it says and leaves the rest as it was" {
            part(token("issuer" to "https://other")[1]).getValue("iss").jsonPrimitive.content shouldBe "https://other"
            part(token("email_verified" to false)[1]).getValue("email_verified").jsonPrimitive.boolean shouldBe false
            part(token("expires_in_seconds" to -60)[1]).getValue("exp").jsonPrimitive.long shouldBe NOW - 60
            token("issuer" to "https://other").let(::verifies) shouldBe true
        }

        "a corrupted signature does not verify, and the rest is the same token" {
            val good = token()
            val bad = token("corrupt_signature" to true)
            bad[0] shouldBe good[0]
            bad[1] shouldBe good[1]
            verifies(bad) shouldBe false
        }

        "a token with algorithm none has no signature and no key id" {
            val parts = PushSigner.token(key, buildJsonObject { put("algorithm", "none") }, NOW).split(".")
            parts.size shouldBe 3
            parts[2] shouldBe ""
            part(parts[0]).getValue("alg").jsonPrimitive.content shouldBe "none"
            part(parts[0]).containsKey("kid") shouldBe false
        }
    })
