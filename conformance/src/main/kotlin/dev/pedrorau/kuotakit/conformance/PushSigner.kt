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

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.Signature
import java.util.Base64

/**
 * Makes the OpenID Connect tokens that a Pub/Sub push subscription sends in `Authorization: Bearer` (ADR 0014),
 * signed with the key of the fake Google, valid or broken in the way a scenario asks. [spec] may hold: `audience`,
 * `email`, `email_verified`, `issuer`, `expires_in_seconds` (negative for an expired token), `algorithm`
 * (`RS256`, or `none` for a token with no signature) and `corrupt_signature`.
 */
internal object PushSigner {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private const val DEFAULT_ISSUER = "https://accounts.google.com"
    private const val DEFAULT_LIFETIME_SECONDS = 3600L
    private const val FLIPPED_BYTE = 5

    fun token(
        key: SigningKey,
        spec: JsonObject,
        nowSeconds: Long = System.currentTimeMillis() / MILLIS,
    ): String {
        val algorithm = spec["algorithm"]?.jsonPrimitive?.content ?: "RS256"
        val header =
            buildJsonObject {
                put("alg", algorithm)
                put("typ", "JWT")
                if (algorithm != "none") put("kid", key.keyId)
            }
        val lifetime = spec["expires_in_seconds"]?.jsonPrimitive?.long ?: DEFAULT_LIFETIME_SECONDS
        val payload =
            buildJsonObject {
                put("iss", spec["issuer"]?.jsonPrimitive?.content ?: DEFAULT_ISSUER)
                put("aud", spec["audience"]?.jsonPrimitive?.content.orEmpty())
                put("sub", "1234567890")
                put("email", spec["email"]?.jsonPrimitive?.content.orEmpty())
                put("email_verified", spec["email_verified"]?.jsonPrimitive?.boolean ?: true)
                put("iat", nowSeconds)
                put("exp", nowSeconds + lifetime)
            }
        val signed = encode(header.toString()) + "." + encode(payload.toString())
        if (algorithm == "none") return "$signed."
        val signature =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(key.privateKey)
                    update(signed.toByteArray())
                }.sign()
        if (spec["corrupt_signature"]?.jsonPrimitive?.boolean ==
            true
        ) {
            signature[FLIPPED_BYTE] = signature[FLIPPED_BYTE].inc()
        }
        return signed + "." + encoder.encodeToString(signature)
    }

    private fun encode(text: String) = encoder.encodeToString(text.toByteArray())

    private const val MILLIS = 1000L
}
