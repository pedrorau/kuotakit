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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The table "Errors" of `docs/api-spec.md`: every error answer of the API has one of these codes with its status.
 * A test keeps this table and the document the same. A plain 404 or 405 (a path or method that is not part of the
 * API) has no body.
 */
internal object ErrorTable {
    val CODES =
        mapOf(
            400 to "invalid_payload",
            401 to "unauthorized",
            402 to "purchase_invalid",
            409 to "token_already_linked",
            429 to "rate_limited",
            500 to "internal_error",
            502 to "store_unavailable",
        )

    /** What is wrong with [reply] as an error answer, or `null` when it is fine or is not an error. */
    fun problem(reply: Reply): String? {
        if (reply.status < MIN_ERROR ||
            (reply.status in setOf(Status.NOT_FOUND, Status.METHOD_NOT_ALLOWED) && reply.body.isBlank())
        ) {
            return null
        }
        val code =
            runCatching {
                Json
                    .parseToJsonElement(
                        reply.body,
                    ).jsonObject
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content
            }.getOrNull()
        val expected = CODES[reply.status]
        return when {
            code == null -> "a ${reply.status} without the error body: ${reply.body.take(SHOWN)}"
            expected != code -> "a ${reply.status} with the code '$code' (docs/api-spec.md says '$expected')"
            else -> null
        }
    }

    private const val MIN_ERROR = Status.BAD_REQUEST
    private const val SHOWN = 100
}
