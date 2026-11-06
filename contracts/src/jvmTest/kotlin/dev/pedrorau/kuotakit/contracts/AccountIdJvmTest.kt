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

package dev.pedrorau.kuotakit.contracts

import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** On the JVM the plain Kotlin SHA-256 can be compared with the platform's own on as many inputs as we like. */
class AccountIdJvmTest {
    private fun reference(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    @Test
    fun agreesWithMessageDigestAroundEveryBlockBoundary() {
        val random = Random(20261106)
        // 55, 56, 63, 64 and 65 bytes are where the padding changes shape
        (0..300).forEach { size ->
            val bytes = random.nextBytes(size)
            assertEquals(reference(bytes), Sha256.hex(bytes), "size $size")
        }
    }

    @Test
    fun agreesWithMessageDigestOnLongInputs() {
        val random = Random(7)
        listOf(1_000, 4_095, 4_096, 65_537, 300_000).forEach { size ->
            val bytes = random.nextBytes(size)
            assertEquals(reference(bytes), Sha256.hex(bytes), "size $size")
        }
    }

    @Test
    fun agreesWithMessageDigestOnTheAppUserIdsTheSdkMakes() {
        val random = Random(99)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.\$-"
        repeat(500) {
            val id = (1..random.nextInt(1, 129)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            assertEquals(reference(id.encodeToByteArray()), playObfuscatedAccountId(id), id)
        }
    }
}
