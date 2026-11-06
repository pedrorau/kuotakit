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

/**
 * The value an app gives Google Play as the `obfuscatedAccountId` of a purchase, and the one a backend requires
 * before it accepts the purchase for that app user (docs/adr/0017): the SHA-256 of the app user id, as 64 lowercase
 * hexadecimal characters.
 *
 * It is a hash on purpose. Google limits the field to 64 characters (an app user id can have 128), asks not to put
 * personal data in it and recommends a one-way hash, and the app user id is a secret of the user that must not leave
 * the app and the backend. The SDKs and the backends all use this one function, so they always agree.
 */
public fun playObfuscatedAccountId(appUserId: String): String = Sha256.hex(appUserId.encodeToByteArray())

/**
 * SHA-256 (FIPS 180-4) in plain Kotlin: the standard library of Kotlin Multiplatform has none, and `contracts/` may
 * use nothing but Kotlin and kotlinx (it is shared with every platform). Checked against the official test vectors
 * and, on the JVM, against `MessageDigest`.
 */
@Suppress("MagicNumber") // the rotations, shifts and sizes are the standard's own; naming each would hide it
internal object Sha256 {
    private const val BLOCK_BYTES = 64
    private const val ROUNDS = 64
    private const val WORD_BYTES = 4
    private const val LENGTH_BYTES = 8

    private val K: IntArray =
        longArrayOf(
            0x428a2f98,
            0x71374491,
            0xb5c0fbcf,
            0xe9b5dba5,
            0x3956c25b,
            0x59f111f1,
            0x923f82a4,
            0xab1c5ed5,
            0xd807aa98,
            0x12835b01,
            0x243185be,
            0x550c7dc3,
            0x72be5d74,
            0x80deb1fe,
            0x9bdc06a7,
            0xc19bf174,
            0xe49b69c1,
            0xefbe4786,
            0x0fc19dc6,
            0x240ca1cc,
            0x2de92c6f,
            0x4a7484aa,
            0x5cb0a9dc,
            0x76f988da,
            0x983e5152,
            0xa831c66d,
            0xb00327c8,
            0xbf597fc7,
            0xc6e00bf3,
            0xd5a79147,
            0x06ca6351,
            0x14292967,
            0x27b70a85,
            0x2e1b2138,
            0x4d2c6dfc,
            0x53380d13,
            0x650a7354,
            0x766a0abb,
            0x81c2c92e,
            0x92722c85,
            0xa2bfe8a1,
            0xa81a664b,
            0xc24b8b70,
            0xc76c51a3,
            0xd192e819,
            0xd6990624,
            0xf40e3585,
            0x106aa070,
            0x19a4c116,
            0x1e376c08,
            0x2748774c,
            0x34b0bcb5,
            0x391c0cb3,
            0x4ed8aa4a,
            0x5b9cca4f,
            0x682e6ff3,
            0x748f82ee,
            0x78a5636f,
            0x84c87814,
            0x8cc70208,
            0x90befffa,
            0xa4506ceb,
            0xbef9a3f7,
            0xc67178f2,
        ).map { it.toInt() }.toIntArray()

    private val INITIAL: IntArray =
        longArrayOf(
            0x6a09e667,
            0xbb67ae85,
            0x3c6ef372,
            0xa54ff53a,
            0x510e527f,
            0x9b05688c,
            0x1f83d9ab,
            0x5be0cd19,
        ).map { it.toInt() }.toIntArray()

    fun hex(message: ByteArray): String =
        digest(message).joinToString("") { word -> word.toUInt().toString(HEX_RADIX).padStart(WORD_BYTES * 2, '0') }

    private const val HEX_RADIX = 16

    private fun digest(message: ByteArray): IntArray {
        val state = INITIAL.copyOf()
        val padded = pad(message)
        val w = IntArray(ROUNDS)
        for (block in 0 until padded.size / BLOCK_BYTES) {
            for (i in 0 until 16) {
                val at = block * BLOCK_BYTES + i * WORD_BYTES
                w[i] = word(padded, at)
            }
            for (i in 16 until ROUNDS) {
                val s0 = w[i - 15].rotr(7) xor w[i - 15].rotr(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotr(17) xor w[i - 2].rotr(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            compress(state, w)
        }
        return state
    }

    private fun compress(
        state: IntArray,
        w: IntArray,
    ) {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in 0 until ROUNDS) {
            val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
            val choose = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + choose + K[i] + w[i]
            val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + majority
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    /** The message, a 1 bit, zeros up to 56 bytes of a block, and the length in bits as 8 big-endian bytes. */
    private fun pad(message: ByteArray): ByteArray {
        val zeros = (BLOCK_BYTES - (message.size + 1 + LENGTH_BYTES) % BLOCK_BYTES) % BLOCK_BYTES
        val padded = ByteArray(message.size + 1 + zeros + LENGTH_BYTES)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        val bits = message.size.toLong() * 8
        for (i in 0 until LENGTH_BYTES) {
            padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        }
        return padded
    }

    private fun word(
        bytes: ByteArray,
        at: Int,
    ): Int =
        ((bytes[at].toInt() and 0xff) shl 24) or
            ((bytes[at + 1].toInt() and 0xff) shl 16) or
            ((bytes[at + 2].toInt() and 0xff) shl 8) or
            (bytes[at + 3].toInt() and 0xff)

    private fun Int.rotr(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}
