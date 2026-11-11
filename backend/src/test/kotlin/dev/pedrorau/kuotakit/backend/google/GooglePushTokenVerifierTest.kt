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

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.auth.oauth2.GooglePublicKeysManager
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.json.webtoken.JsonWebSignature
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import dev.pedrorau.kuotakit.backend.domain.StoreUnavailableException
import dev.pedrorau.kuotakit.backend.testing.TestKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val AUDIENCE = "https://kuotakit.example.com/v1/webhooks/google"
private const val ACCOUNT = "push@my-project.iam.gserviceaccount.com"
private const val GOOGLE_ISSUER = "https://accounts.google.com"
private val JSON = GsonFactory.getDefaultInstance()

private val trusted = TestKey.generate()
private val stranger = TestKey.generate()

@Suppress("LongParameterList") // a builder with defaults: the long list is what keeps the tests short
private fun token(
    key: TestKey = trusted,
    issuer: String = GOOGLE_ISSUER,
    audience: String = AUDIENCE,
    email: String? = ACCOUNT,
    emailVerified: Boolean? = true,
    expiresInSeconds: Long = 3600,
): String {
    val now = System.currentTimeMillis() / 1000
    val payload =
        GoogleIdToken
            .Payload()
            .setIssuer(issuer)
            .setAudience(audience)
            .setSubject("123456789")
            .setIssuedAtTimeSeconds(now - 10)
            .setExpirationTimeSeconds(now + expiresInSeconds)
            .setEmail(email)
            .setEmailVerified(emailVerified)
    val header =
        JsonWebSignature
            .Header()
            .setAlgorithm("RS256")
            .setType("JWT")
            .setKeyId("kid1")
    return JsonWebSignature.signUsingRsaSha256(key.privateKey, JSON, header, payload)
}

/** A verifier that gets Google's signing certificates from [certs] instead of from the internet. */
private fun verifier(certs: () -> LowLevelHttpResponse = { certificates(trusted) }): GooglePushTokenVerifier {
    val transport =
        object : MockHttpTransport() {
            override fun buildRequest(
                method: String,
                url: String,
            ) = object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse = certs()
            }
        }
    val keys = GooglePublicKeysManager.Builder(transport, JSON).build()
    val google = GoogleIdTokenVerifier.Builder(keys).setAudience(listOf(AUDIENCE)).build()
    return GooglePushTokenVerifier(google, ACCOUNT)
}

private fun certificates(key: TestKey): LowLevelHttpResponse =
    MockLowLevelHttpResponse()
        .setContentType("application/json")
        .setContent(buildJsonObject { put("kid1", key.pem) }.toString())

class GooglePushTokenVerifierTest :
    StringSpec({
        "a token signed by Google for our audience from our service account is accepted" {
            verifier().verify(token()) shouldBe true
        }

        "a token for another audience is refused" {
            verifier().verify(token(audience = "https://evil.example.com/hook")) shouldBe false
        }

        "a token from another service account is refused" {
            verifier().verify(token(email = "attacker@other-project.iam.gserviceaccount.com")) shouldBe false
            verifier().verify(token(email = null)) shouldBe false
        }

        "an email that Google did not verify is refused" {
            verifier().verify(token(emailVerified = false)) shouldBe false
            verifier().verify(token(emailVerified = null)) shouldBe false
        }

        "a token from another issuer is refused, and both spellings of Google's are accepted" {
            verifier().verify(token(issuer = "https://evil.example.com")) shouldBe false
            verifier().verify(token(issuer = "accounts.google.com")) shouldBe true
        }

        "an expired token is refused" {
            verifier().verify(token(expiresInSeconds = -3600)) shouldBe false
        }

        "a token signed with a key Google does not publish is refused" {
            verifier().verify(token(key = stranger)) shouldBe false
        }

        "garbage is refused, never an error" {
            listOf(
                "",
                "x",
                "a.b.c",
                "Bearer abc",
                token().dropLast(5),
                token().substringBeforeLast('.') + ".",
            ).forEach {
                verifier().verify(it) shouldBe false
            }
        }

        "an unsigned token and one signed with the public certificate as a secret are refused" {
            val payload = token().split('.')[1]
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val none = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray()) + ".$payload."
            verifier().verify(none) shouldBe false
            val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT","kid":"kid1"}""".toByteArray())
            val mac =
                Mac
                    .getInstance(
                        "HmacSHA256",
                    ).apply { init(SecretKeySpec(trusted.pem.toByteArray(), "HmacSHA256")) }
            val signature = encoder.encodeToString(mac.doFinal("$header.$payload".toByteArray()))
            verifier().verify("$header.$payload.$signature") shouldBe false
        }

        "garbage is refused even while Google's keys are unreachable, so a fake push is never retried" {
            val down = verifier { throw IOException("unreachable") }
            listOf("", "x", "a.b.c", "!!.!!.!!").forEach { down.verify(it) shouldBe false }
        }

        "if Google's signing keys cannot be fetched the push is retried, not refused for good" {
            val down = verifier { throw IOException("unreachable") }
            shouldThrow<StoreUnavailableException> { down.verify(token()) }
            val error = verifier { MockLowLevelHttpResponse().setStatusCode(503).setContent("{}") }
            shouldThrow<StoreUnavailableException> { error.verify(token()) }
        }
    })
