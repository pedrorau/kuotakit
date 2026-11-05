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

package dev.pedrorau.kuotakit.openapi

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.HealthResponse
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Who may call an endpoint. */
enum class Auth { NONE, API_KEY }

/** A JSON body: its schema (built lazily, because `$ref`s need the schema builder) and an example of it. */
class Body(
    val schema: (SchemaBuilder) -> JsonObject,
    val example: JsonElement?,
)

class Response(
    val status: Int,
    val description: String,
    val body: Body? = null,
)

data class Endpoint(
    val method: String,
    val path: String,
    val operationId: String,
    val tag: String,
    val summary: String,
    val description: String,
    val auth: Auth,
    val pathParameters: Map<String, String> = emptyMap(),
    val request: Body? = null,
    val responses: List<Response>,
)

private fun model(
    serializer: KSerializer<*>,
    example: JsonElement,
): Body = Body({ it.schemaOf(serializer.descriptor) }, example)

private fun error(
    status: Int,
    code: ErrorCode,
    description: String,
    message: String,
): Response = Response(status, description, model(ErrorResponse.serializer(), Examples.error(code, message)))

private const val HTTP_UNAUTHORIZED = 401

private val unauthorized =
    error(HTTP_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "The API key is missing or wrong.", "Missing or invalid API key.")

/** Webhooks have no API key: a 401 means the store's own proof (Pub/Sub OIDC token, Apple JWS) did not verify. */
private fun webhookUnauthorized(proof: String) =
    error(
        HTTP_UNAUTHORIZED,
        ErrorCode.UNAUTHORIZED,
        "The $proof could not be verified.",
        "Notification verification failed.",
    )

/** The body of a store notification is defined by the store, not by KuotaKit. */
private fun storeNotification(description: String): Body =
    Body(
        {
            buildJsonObject {
                put("type", "object")
                put("description", description)
                put("additionalProperties", JsonPrimitive(true))
            }
        },
        null,
    )

/**
 * Every endpoint of the API (docs/api-spec.md). The paths are not in the models, so they live here; the models
 * give the schemas. Adding a backend endpoint means adding it here too.
 */
val ENDPOINTS: List<Endpoint> =
    listOf(
        Endpoint(
            method = "GET",
            path = "/health",
            operationId = "getHealth",
            tag = "Health",
            summary = "Liveness check",
            description = "Answers 200 while the backend is running. It needs no API key.",
            auth = Auth.NONE,
            responses =
                listOf(
                    Response(200, "The backend is up.", model(HealthResponse.serializer(), Examples.health)),
                ),
        ),
        Endpoint(
            method = "GET",
            path = "/v1/offerings",
            operationId = "getOfferings",
            tag = "Offerings",
            summary = "List the offerings",
            description = "Returns the offerings defined in `entitlements.yaml`. Any API key can call it.",
            auth = Auth.API_KEY,
            responses =
                listOf(
                    Response(200, "The offerings.", model(OfferingsResponse.serializer(), Examples.offerings)),
                    unauthorized,
                ),
        ),
        Endpoint(
            method = "POST",
            path = "/v1/subscribers/{app_user_id}/receipts",
            operationId = "postReceipt",
            tag = "Subscribers",
            summary = "Submit a purchase",
            description =
                "Called by the SDK after a store purchase. The backend validates it with the store, saves it, " +
                    "acknowledges it (Android) and returns the updated customer info. On any store failure it never " +
                    "grants access (ADR 0003). An app key may only submit purchases for its own user.",
            auth = Auth.API_KEY,
            pathParameters = mapOf("app_user_id" to "The id of the app user, for example `\$anon_6f1c`."),
            request = model(ReceiptRequest.serializer(), Examples.playStoreReceipt),
            responses =
                listOf(
                    Response(
                        200,
                        "The purchase is valid; the updated customer info.",
                        model(CustomerInfo.serializer(), Examples.customerInfo),
                    ),
                    error(
                        400,
                        ErrorCode.INVALID_PAYLOAD,
                        "The body is malformed.",
                        "The receipt is missing purchase_token.",
                    ),
                    unauthorized,
                    error(
                        402,
                        ErrorCode.PURCHASE_INVALID,
                        "The store says the purchase is not valid.",
                        "The store rejected the purchase.",
                    ),
                    error(
                        403,
                        ErrorCode.FORBIDDEN,
                        "The key may not act on this user.",
                        "This key cannot submit purchases for that user.",
                    ),
                    error(
                        409,
                        ErrorCode.TOKEN_ALREADY_LINKED,
                        "The purchase token is linked to another user.",
                        "The purchase token belongs to another user.",
                    ),
                    error(
                        502,
                        ErrorCode.STORE_UNAVAILABLE,
                        "The store API is unavailable; access is not granted.",
                        "The store could not be reached.",
                    ),
                ),
        ),
        Endpoint(
            method = "GET",
            path = "/v1/subscribers/{app_user_id}",
            operationId = "getSubscriber",
            tag = "Subscribers",
            summary = "Get a subscriber",
            description =
                "Returns what the user has bought and what they may access. An app key (`pk_`) can read its own " +
                    "user; a secret key (`sk_`) can read any user.",
            auth = Auth.API_KEY,
            pathParameters = mapOf("app_user_id" to "The id of the app user, for example `\$anon_6f1c`."),
            responses =
                listOf(
                    Response(200, "The customer info.", model(CustomerInfo.serializer(), Examples.customerInfo)),
                    unauthorized,
                    error(
                        403,
                        ErrorCode.FORBIDDEN,
                        "An app key asked for another user.",
                        "This key cannot read that user.",
                    ),
                    error(404, ErrorCode.NOT_FOUND, "The user does not exist.", "No subscriber with that id."),
                ),
        ),
        Endpoint(
            method = "POST",
            path = "/v1/webhooks/google",
            operationId = "receiveGoogleNotification",
            tag = "Webhooks",
            summary = "Google Play real-time developer notification",
            description =
                "Cloud Pub/Sub push of a Play RTDN. The backend verifies the Pub/Sub OIDC token, then re-fetches the " +
                    "subscription from the Play Developer API: the notification body alone is never trusted.",
            auth = Auth.NONE,
            request = storeNotification("The Pub/Sub push envelope, as defined by Google."),
            responses =
                listOf(
                    Response(204, "The notification was processed."),
                    error(400, ErrorCode.INVALID_PAYLOAD, "The body is malformed.", "Not a Pub/Sub push message."),
                    webhookUnauthorized("Pub/Sub OIDC token"),
                ),
        ),
        Endpoint(
            method = "POST",
            path = "/v1/webhooks/apple",
            operationId = "receiveAppleNotification",
            tag = "Webhooks",
            summary = "App Store server notification v2",
            description =
                "App Store Server Notification v2. The backend verifies the `signedPayload` JWS against Apple's root " +
                    "certificate chain before updating any state.",
            auth = Auth.NONE,
            request = storeNotification("`{\"signedPayload\": \"<JWS>\"}`, as defined by Apple."),
            responses =
                listOf(
                    Response(204, "The notification was processed."),
                    error(400, ErrorCode.INVALID_PAYLOAD, "The body is malformed.", "Missing signedPayload."),
                    webhookUnauthorized("signedPayload JWS"),
                ),
        ),
    )
