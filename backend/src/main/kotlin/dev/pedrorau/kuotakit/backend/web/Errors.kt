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

import dev.pedrorau.kuotakit.contracts.ErrorBody
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

fun errorResponse(
    code: ErrorCode,
    message: String,
): ErrorResponse = ErrorResponse(ErrorBody(code, message))

/** Every error of the API has the body of `ErrorResponse` (docs/api-spec.md), including the ones Ktor raises itself. */
internal fun Application.installErrorHandling() {
    install(StatusPages) {
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                errorResponse(ErrorCode.UNAUTHORIZED, "Missing or invalid API key."),
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                errorResponse(ErrorCode.INVALID_PAYLOAD, "The request is malformed."),
            )
        }
        // Fail closed: an unexpected error is a 500 that grants nothing, and the details stay in the log.
        exception<Throwable> { call, cause ->
            call.application.log.error(
                "Unhandled error on ${call.request.local.method.value} ${redactedPath(call.request.local.uri)}",
                cause,
            )
            call.respond(HttpStatusCode.InternalServerError, errorResponse(ErrorCode.INTERNAL_ERROR, "Internal error."))
        }
    }
}

private val SUBSCRIBER_PATH = Regex("^(/v1/subscribers/)[^/?]*")

/** The path for the log: an app user id is a secret (docs/api-spec.md) and must never be logged. */
internal fun redactedPath(uri: String): String = uri.replace(SUBSCRIBER_PATH, "$1{app_user_id}").substringBefore('?')
