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

package dev.pedrorau.kuotakit.sandbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SettingsValidationTest {
    private val key = "pk_0123456789abcdef"

    private fun valid(
        url: String,
        apiKey: String = key,
    ) = assertIs<Validation.Valid>(SettingsValidation.validate(url, apiKey))

    private fun problem(
        url: String,
        apiKey: String = key,
    ) = assertIs<Validation.Invalid>(SettingsValidation.validate(url, apiKey)).problem

    @Test
    fun `an https address and a public key are accepted, trimmed and without the trailing slash`() {
        assertEquals(
            Validation.Valid("https://sandbox.example.com", key),
            SettingsValidation.validate("  https://sandbox.example.com/ ", " $key "),
        )
        assertEquals("https://host.example:8443/api", valid("https://host.example:8443/api/").baseUrl)
    }

    @Test
    fun `the scheme and the host may be written in capitals, and the address is kept with a lower case scheme`() {
        assertEquals("https://Host.Example", valid("HTTPS://Host.Example/").baseUrl)
        assertEquals("http://LOCALHOST:8080", valid("HTTP://LOCALHOST:8080").baseUrl)
        assertTrue(problem("HTTP://sandbox.example.com").contains("https"))
    }

    @Test
    fun `plain http is accepted only for the computer that runs the emulator`() {
        listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://10.0.2.2:8080").forEach { valid(it) }
        listOf("http://sandbox.example.com", "http://192.168.1.20:8080", "http://10.0.2.2.evil.example").forEach {
            assertTrue(problem(it).contains("https"), it)
        }
    }

    @Test
    fun `an address that is not only an address is refused`() {
        listOf(
            "",
            "not a url",
            "ftp://host",
            "https://user:pass@host.example",
            "https://host.example/?k=v",
            "https://host.example/#x",
            "https://",
        ).forEach {
            assertIs<Validation.Invalid>(SettingsValidation.validate(it, key), it)
        }
    }

    @Test
    fun `a secret key is refused with its own message, and so is anything that is not a public key`() {
        assertTrue(problem("https://host.example", "sk_0123456789abcdef").contains("secret key"))
        listOf("", "pk_", "pk_ with space", "abc_0123456789abcdef", "PK_0123456789abcdef").forEach {
            assertIs<Validation.Invalid>(SettingsValidation.validate("https://host.example", it), it)
        }
    }

    @Test
    fun `an empty address says that the app is not set up yet, instead of a technical error`() {
        assertTrue(problem("").contains("Not set up yet"))
        assertTrue(problem("   ", "").contains("Not set up yet"))
        assertTrue(problem("not a url").contains("not a valid URL"))
    }

    @Test
    fun `the user id is shown only in part`() {
        assertEquals("anon_a_sec…", truncatedUserId("anon_a_secret_user_id"))
        assertEquals("short", truncatedUserId("short"))
    }
}
