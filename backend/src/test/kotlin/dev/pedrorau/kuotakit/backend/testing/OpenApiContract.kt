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

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import io.kotest.matchers.shouldBe
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.io.File

/**
 * Checks what the backend really sends against `docs/api/openapi.json`, the contract generated from the models
 * (ADR 0007, ADR 0012). A small preview of what the conformance suite will do against any backend.
 */
object OpenApiContract {
    private val validator =
        OpenApiInteractionValidator.createFor(File("../docs/api/openapi.json").readText()).build()

    suspend fun assertMatches(
        method: HttpMethod,
        path: String,
        response: HttpResponse,
        authorization: String? = null,
    ) {
        val request =
            SimpleRequest
                .Builder(method.value, path)
                .apply { authorization?.let { withHeader(HttpHeaders.Authorization, it) } }
                .build()
        val body = response.bodyAsText()
        val reply =
            SimpleResponse
                .Builder(response.status.value)
                .withContentType(response.headers[HttpHeaders.ContentType])
                .withBody(body)
                .build()
        val report = validator.validate(request, reply)
        report.messages.map { it.message } shouldBe emptyList()
    }

    /** Only the response: for requests that break the contract on purpose (no key), the reply must still match it. */
    suspend fun assertResponseMatches(
        method: HttpMethod,
        path: String,
        response: HttpResponse,
    ) {
        val reply =
            SimpleResponse
                .Builder(response.status.value)
                .withContentType(response.headers[HttpHeaders.ContentType])
                .withBody(response.bodyAsText())
                .build()
        validator
            .validateResponse(
                path,
                Request.Method.valueOf(method.value),
                reply,
            ).messages
            .map { it.message } shouldBe
            emptyList()
    }
}
