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

package dev.pedrorau.kuotakit.backend.testing

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.json.webtoken.JsonWebSignature
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64

/** An RSA key pair and its self-signed certificate, made with the JDK's own `keytool` (nothing is stored in git). */
class TestKey(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val pem: String
        get() =
            "-----BEGIN CERTIFICATE-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded) +
                "\n-----END CERTIFICATE-----\n"

    /** A token like the one a Pub/Sub push subscription sends, signed with this key. */
    fun pushToken(
        audience: String,
        email: String,
        issuer: String = "https://accounts.google.com",
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
                .setEmailVerified(true)
        val header =
            JsonWebSignature
                .Header()
                .setAlgorithm("RS256")
                .setType("JWT")
                .setKeyId("kid1")
        return JsonWebSignature.signUsingRsaSha256(privateKey, GsonFactory.getDefaultInstance(), header, payload)
    }

    companion object {
        fun generate(): TestKey {
            val dir = Files.createTempDirectory("push-key").toFile()
            val keystore = File(dir, "k.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process =
                ProcessBuilder(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    "k",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "30",
                    "-dname",
                    "CN=test",
                    "-keystore",
                    keystore.path,
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    "changeit",
                ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "keytool failed: $output" }
            val store =
                KeyStore
                    .getInstance(
                        "PKCS12",
                    ).apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
            return TestKey(
                store.getKey("k", "changeit".toCharArray()) as PrivateKey,
                store.getCertificate("k") as X509Certificate,
            ).also { dir.deleteRecursively() }
        }
    }
}
