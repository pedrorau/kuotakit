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

package dev.pedrorau.kuotakit.backend

import dev.pedrorau.kuotakit.backend.google.PubSubPushParser
import dev.pedrorau.kuotakit.backend.web.PushUnauthorizedException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/** Far more than a receipt or a notification needs; stops a huge body from being buffered before it is checked. */
internal const val MAX_BODY_BYTES = 16L * 1024

/** The body as text, or `null` if it is over [MAX_BODY_BYTES]. The caller decides what to answer. */
internal suspend fun ApplicationCall.readBoundedText(): String? {
    val bytes = receiveChannel().readBuffer(MAX_BODY_BYTES + 1).readByteArray()
    return if (bytes.size > MAX_BODY_BYTES) null else bytes.decodeToString()
}

/** The token of an `Authorization: Bearer <token>` header, or `null`. */
private fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring("Bearer ".length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Not behind an API key: Pub/Sub cannot send one. The proof is the OIDC token Pub/Sub signs (ADR 0014). Every refusal
 * is a [PushUnauthorizedException], answered as a 401 by the error handling.
 */
internal fun Route.googleWebhook(services: Services) {
    post("/v1/webhooks/google") {
        val verifier = services.pushVerifier ?: throw PushUnauthorizedException("the webhook is not configured")
        val token = call.bearerToken()
        if (token == null || !verifier.verify(token)) throw PushUnauthorizedException("no valid bearer token")
        // From here on the push is known to come from our Pub/Sub subscription. One that cannot be read can never be
        // read later either, so it is acknowledged and logged: a 4xx would make Pub/Sub send it again for days.
        val push = call.readBoundedText()?.let { body -> runCatching { PubSubPushParser.parse(body) }.getOrNull() }
        if (push == null) {
            call.application.log.warn("Play notification dropped: too large or not a Play notification")
        } else {
            call.application.log.info("Play notification handled: ${services.notifications.handle(push)}")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
