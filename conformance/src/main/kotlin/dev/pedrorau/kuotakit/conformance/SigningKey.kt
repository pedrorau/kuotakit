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

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The key the fake Google signs Pub/Sub push tokens with, and the two formats it publishes its public part in
 * (ADR 0018): a map from key id to a PEM certificate, and a JWKS. The JDK cannot make a certificate, so `keytool`
 * (which ships with every JRE) does, once at start.
 */
internal class SigningKey private constructor(
    val keyId: String,
    val privateKey: PrivateKey,
    private val certificate: X509Certificate,
) {
    val certificatePem: String
        get() = pem("CERTIFICATE", certificate.encoded)

    /** What `/oauth2/v1/certs` answers: `{"<key id>": "<PEM certificate>"}`. */
    fun pemMapJson(): String = """{"$keyId":"${certificatePem.replace("\n", "\\n")}"}"""

    /** What `/oauth2/v3/certs` answers. */
    fun jwksJson(): String {
        val key = certificate.publicKey as RSAPublicKey
        return """{"keys":[{"kty":"RSA","alg":"RS256","use":"sig","kid":"$keyId","n":"${base64Url(
            key.modulus,
        )}","e":"${base64Url(key.publicExponent)}"}]}"""
    }

    companion object {
        private const val ALIAS = "fake-google"
        private const val PASSWORD = "changeit"
        private const val KEYTOOL_SECONDS = 60L
        private const val PEM_LINE = 64

        fun generate(keyId: String = "fake-google-key-1"): SigningKey {
            val dir = Files.createTempDirectory("conformance-key").toFile()
            try {
                val store = File(dir, "key.p12")
                run(
                    "-genkeypair",
                    "-alias",
                    ALIAS,
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "3650",
                    "-dname",
                    "CN=fake-google",
                    "-keystore",
                    store.path,
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    PASSWORD,
                    "-keypass",
                    PASSWORD,
                )
                val keyStore = KeyStore.getInstance("PKCS12")
                store.inputStream().use { keyStore.load(it, PASSWORD.toCharArray()) }
                val key = keyStore.getKey(ALIAS, PASSWORD.toCharArray()) as PrivateKey
                return SigningKey(keyId, key, keyStore.getCertificate(ALIAS) as X509Certificate)
            } finally {
                dir.deleteRecursively()
            }
        }

        private fun run(vararg arguments: String) {
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val process = ProcessBuilder(listOf(keytool) + arguments).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(
                process.waitFor(KEYTOOL_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0,
            ) { "keytool failed: $output" }
        }

        private fun base64Url(number: BigInteger): String {
            val bytes =
                number.toByteArray().let {
                    if (it.size > 1 &&
                        it[0] == 0.toByte()
                    ) {
                        it.copyOfRange(1, it.size)
                    } else {
                        it
                    }
                }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        internal fun pem(
            type: String,
            der: ByteArray,
        ): String =
            "-----BEGIN $type-----\n" +
                Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der) +
                "\n-----END $type-----\n"
    }
}
