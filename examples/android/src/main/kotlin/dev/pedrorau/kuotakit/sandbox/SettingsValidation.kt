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

import java.net.URI
import java.net.URISyntaxException

/** The result of checking what the user typed in Settings. */
internal sealed interface Validation {
    /** Usable: the address without a trailing slash and the key, both trimmed. */
    data class Valid(
        val baseUrl: String,
        val apiKey: String,
    ) : Validation

    data class Invalid(
        val problem: String,
    ) : Validation
}

/**
 * The backend address and the key of the app. The address must be `https`, except for the computer that runs the
 * emulator (the debug build allows plain http to those addresses only); the key must be the public one (`pk_...`):
 * a secret key must never be in an app, and the SDK refuses it too.
 */
internal object SettingsValidation {
    private val PLAIN_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private const val MIN_KEY_LENGTH = 8

    fun validate(
        baseUrl: String,
        apiKey: String,
    ): Validation {
        val address = lowerCaseScheme(baseUrl.trim().trimEnd('/'))
        val key = apiKey.trim()
        val problem = addressProblem(address) ?: keyProblem(key)
        return if (problem == null) Validation.Valid(address, key) else Validation.Invalid(problem)
    }

    private fun addressProblem(address: String): String? {
        val uri = parse(address)
        val host = uri?.host
        return when {
            address.isEmpty() -> {
                "Not set up yet: type the address of your backend (https://...)."
            }

            uri == null || host == null -> {
                "The backend address is not a valid URL (for example https://host)."
            }

            uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null -> {
                "The backend address must be only the address, with no user, query or fragment."
            }

            uri.scheme == "https" -> {
                null
            }

            uri.scheme == "http" && host.lowercase() in PLAIN_HTTP_HOSTS -> {
                null
            }

            else -> {
                "The backend address must be https (http is allowed only for localhost and 10.0.2.2 in a debug build)."
            }
        }
    }

    private fun keyProblem(key: String): String? =
        when {
            key.startsWith("sk_") -> {
                "That is a secret key (sk_...). Never put it in an app: use the public key (pk_...)."
            }

            !key.startsWith("pk_") || key.length < MIN_KEY_LENGTH || key.any { it.isWhitespace() } -> {
                "The key must be the public key of the app (pk_... with no spaces)."
            }

            else -> {
                null
            }
        }

    /** `HTTPS://host` is the same address: the scheme is written in lower case, as the SDK expects it. */
    private fun lowerCaseScheme(address: String): String {
        val end = address.indexOf("://")
        return if (end > 0) address.substring(0, end).lowercase() + address.substring(end) else address
    }

    private fun parse(address: String): URI? =
        try {
            URI(address)
        } catch (_: URISyntaxException) {
            null
        }
}

/** The app user id is a secret of the user (ADR 0009): the screen shows only its beginning. */
internal fun truncatedUserId(id: String): String =
    if (id.length <=
        SHOWN_ID_CHARACTERS
    ) {
        id
    } else {
        id.take(SHOWN_ID_CHARACTERS) + "…"
    }

private const val SHOWN_ID_CHARACTERS = 10
