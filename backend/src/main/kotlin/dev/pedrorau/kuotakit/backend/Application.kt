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

import dev.pedrorau.kuotakit.backend.domain.EntitlementCalculator
import dev.pedrorau.kuotakit.backend.domain.toOfferingsResponse
import dev.pedrorau.kuotakit.backend.web.errorResponse
import dev.pedrorau.kuotakit.backend.web.installErrorHandling
import dev.pedrorau.kuotakit.contracts.APP_USER_ID_PATTERN
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.HealthResponse
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private const val API_AUTH = "api"
private val APP_USER_ID = Regex(APP_USER_ID_PATTERN)

internal fun Application.module(services: Services) {
    install(ContentNegotiation) { json(kuotaKitJson) }
    install(Authentication) {
        bearer(API_AUTH) {
            // A header Ktor cannot parse (a tab, two Authorization headers...) is an unauthenticated call,
            // not a bad request.
            authHeader { call -> runCatching { call.request.parseAuthorizationHeader() }.getOrNull() }
            authenticate { credential -> services.apiKeys.authenticate(credential.token) }
        }
    }
    installErrorHandling()

    val calculator = EntitlementCalculator(services.entitlements)
    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok"))
        }

        authenticate(API_AUTH) {
            get("/v1/offerings") {
                call.respond(services.entitlements.toOfferingsResponse())
            }

            get("/v1/subscribers/{app_user_id}") {
                val appUserId = call.parameters["app_user_id"].orEmpty()
                if (!APP_USER_ID.matches(appUserId)) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        errorResponse(ErrorCode.INVALID_PAYLOAD, "Invalid app user id."),
                    )
                    return@get
                }
                val purchases = services.purchases.purchasesOf(appUserId)
                call.respond(calculator.customerInfo(appUserId, purchases, services.clock.now()))
            }
        }
    }
}
