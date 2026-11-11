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

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse

/**
 * `docs/api/openapi.json`, the contract generated from the models (ADR 0007, ADR 0012), used to check every answer.
 * An answer the document does not describe (a plain 404 or 405, a status it does not list) is not a violation: that is
 * what [describes] is for.
 */
internal class Contract(
    document: String,
) {
    private val validator = OpenApiInteractionValidator.createFor(document).build()

    /** The ways [reply] breaks the contract; empty when it matches or when the contract says nothing about it. */
    fun violations(
        method: String,
        path: String,
        reply: Reply,
    ): List<String> = check(method, path, reply).filterNot { it.key in NOT_DESCRIBED }.map { it.message }

    /** Whether the document describes this answer at all (the path, the method and the status). */
    fun describes(
        method: String,
        path: String,
        reply: Reply,
    ): Boolean = check(method, path, reply).none { it.key in NOT_DESCRIBED }

    private fun check(
        method: String,
        path: String,
        reply: Reply,
    ) = validator
        .validateResponse(
            path,
            Request.Method.valueOf(method),
            SimpleResponse
                .Builder(reply.status)
                .withContentType(reply.header("Content-Type"))
                .withBody(reply.body)
                .build(),
        ).messages

    companion object {
        private val NOT_DESCRIBED =
            setOf(
                "validation.response.status.unknown",
                "validation.request.path.missing",
                "validation.request.operation.notAllowed",
            )

        fun bundled(): Contract =
            Contract(
                Contract::class.java
                    .getResourceAsStream("/contract/openapi.json")
                    ?.use { it.readBytes().decodeToString() }
                    ?: error("The OpenAPI document is not in the jar"),
            )
    }
}
