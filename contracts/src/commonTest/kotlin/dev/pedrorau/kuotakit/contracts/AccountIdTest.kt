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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The official SHA-256 test vectors (FIPS 180-4, NIST), so the plain Kotlin version is right on every platform. */
class AccountIdTest {
    private fun sha256(text: String) = Sha256.hex(text.encodeToByteArray())

    @Test
    fun theEmptyMessage() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(""))
    }

    @Test
    fun abc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc"))
    }

    @Test
    fun theMessageOf448BitsThatNeedsASecondBlockForItsPadding() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test
    fun theMessageOf896Bits() {
        assertEquals(
            "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1",
            sha256(
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnop" +
                    "jklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu",
            ),
        )
    }

    @Test
    fun aMillionTimesA() {
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", sha256("a".repeat(1_000_000)))
    }

    @Test
    fun theAccountIdIs64LowercaseHexCharactersForAnyValidAppUserId() {
        listOf("a", "\$anon_6f1c", "x".repeat(128), "user.name-1_2\$").forEach { id ->
            val value = playObfuscatedAccountId(id)
            assertEquals(64, value.length, id)
            assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' }, value)
        }
    }

    @Test
    fun differentIdsGetDifferentAccountIdsAndTheSameIdAlwaysTheSame() {
        assertEquals(playObfuscatedAccountId("anon_1"), playObfuscatedAccountId("anon_1"))
        assertNotEquals(playObfuscatedAccountId("anon_1"), playObfuscatedAccountId("anon_2"))
        assertNotEquals(playObfuscatedAccountId("anon_1"), playObfuscatedAccountId("Anon_1"))
    }

    @Test
    fun theAccountIdDoesNotContainTheAppUserId() {
        assertTrue("anon_6f1c" !in playObfuscatedAccountId("anon_6f1c"))
    }
}
