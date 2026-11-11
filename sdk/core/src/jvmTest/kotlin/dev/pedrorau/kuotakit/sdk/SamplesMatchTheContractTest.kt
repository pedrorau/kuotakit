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

package dev.pedrorau.kuotakit.sdk

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleResponse
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The answers the tests of the SDK use are checked against `docs/api/openapi.json`, so the tests cannot drift from
 * what a backend really sends (ADR 0007, ADR 0012).
 */
class SamplesMatchTheContractTest {
    private val validator =
        OpenApiInteractionValidator
            .createFor(
                File("../../docs/api/openapi.json").readText(),
            ).build()

    private fun problems(
        method: Request.Method,
        path: String,
        status: Int,
        body: String,
    ) = validator
        .validateResponse(
            path,
            method,
            SimpleResponse
                .Builder(status)
                .withContentType("application/json")
                .withBody(body)
                .build(),
        ).messages
        .map { it.message }

    @Test
    fun theSuccessfulAnswersMatch() {
        assertEquals(emptyList(), problems(Request.Method.GET, "/v1/offerings", 200, Samples.OFFERINGS))
        assertEquals(emptyList(), problems(Request.Method.GET, "/v1/subscribers/anon", 200, Samples.CUSTOMER_EMPTY))
        assertEquals(emptyList(), problems(Request.Method.GET, "/v1/subscribers/anon", 200, Samples.CUSTOMER_PREMIUM))
        assertEquals(
            emptyList(),
            problems(Request.Method.POST, "/v1/subscribers/anon/receipts", 200, Samples.CUSTOMER_PREMIUM),
        )
    }

    @Test
    fun theErrorAnswersMatchWhereTheContractDescribesThem() {
        val errors =
            mapOf(
                400 to "/v1/subscribers/anon/receipts",
                401 to "/v1/offerings",
                402 to "/v1/subscribers/anon/receipts",
                409 to "/v1/subscribers/anon/receipts",
                429 to "/v1/offerings",
                502 to "/v1/subscribers/anon/receipts",
                500 to "/v1/offerings",
            )
        errors.forEach { (status, path) ->
            val method = if (path.endsWith("receipts")) Request.Method.POST else Request.Method.GET
            assertEquals(emptyList(), problems(method, path, status, Samples.ERRORS.getValue(status)), "status $status")
        }
    }
}
