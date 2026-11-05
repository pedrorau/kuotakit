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

package dev.pedrorau.kuotakit.backend.web

import java.security.MessageDigest

enum class KeyKind { APP, SECRET }

/** Who is calling: the kind of key they presented. */
class ApiKeyPrincipal(
    val kind: KeyKind,
)

/**
 * The API keys the backend accepts (`pk_` for the app, `sk_` for the developer's servers). Only the SHA-256 of each
 * key is kept, and every comparison goes through all of them in constant time, so neither a memory dump nor the
 * response time tells an attacker anything about a key.
 */
class ApiKeys(
    appKeys: List<String>,
    secretKeys: List<String>,
) {
    private val hashed: List<Pair<ByteArray, KeyKind>> =
        appKeys.map { sha256(it) to KeyKind.APP } + secretKeys.map { sha256(it) to KeyKind.SECRET }

    fun authenticate(token: String): ApiKeyPrincipal? {
        val presented = sha256(token)
        var match: KeyKind? = null
        for ((hash, kind) in hashed) {
            if (MessageDigest.isEqual(presented, hash)) match = kind // no early exit: always compare every key
        }
        return match?.let { ApiKeyPrincipal(it) }
    }

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
}
