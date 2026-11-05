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

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

private const val APP_KEY = "Bearer pk_test"

private fun JsonElement.refs(): List<String> =
    when (this) {
        is JsonObject -> {
            flatMap { (key, value) ->
                if (key ==
                    "\$ref"
                ) {
                    listOf((value as JsonPrimitive).content)
                } else {
                    value.refs()
                }
            }
        }

        is kotlinx.serialization.json.JsonArray -> {
            flatMap { it.refs() }
        }

        else -> {
            emptyList()
        }
    }

class OpenApiDocumentTest :
    StringSpec({
        val spec = OpenApiDocument.render()
        val document = Json.parseToJsonElement(spec).jsonObject
        // `build()` parses the document and fails if it is not valid OpenAPI or a $ref does not resolve.
        val validator = OpenApiInteractionValidator.createFor(spec).build()

        fun concrete(path: String) = path.replace("{app_user_id}", "anon_1")

        fun requestOf(
            endpoint: Endpoint,
            body: JsonElement?,
        ) = SimpleRequest
            .Builder(endpoint.method, concrete(endpoint.path))
            .withHeader("Authorization", APP_KEY)
            .apply { if (body != null) withContentType("application/json").withBody(body.toString()) }
            .build()

        fun responseOf(
            status: Int,
            body: JsonElement?,
        ) = SimpleResponse
            .Builder(status)
            .apply { if (body != null) withContentType("application/json").withBody(body.toString()) }
            .build()

        fun Endpoint.validateResponse(
            status: Int,
            body: JsonElement?,
        ) = validator.validateResponse(concrete(path), Request.Method.valueOf(method), responseOf(status, body))

        "the document is OpenAPI 3.1 and describes every endpoint of the table" {
            document.getValue("openapi") shouldBe JsonPrimitive("3.1.0")
            val paths = document.getValue("paths").jsonObject
            ENDPOINTS.forEach { endpoint ->
                paths.getValue(endpoint.path).jsonObject.containsKey(endpoint.method.lowercase()) shouldBe true
            }
        }

        "operation ids are unique" {
            val ids = ENDPOINTS.map { it.operationId }
            ids.toSet().size shouldBe ids.size
        }

        "every reference points to an existing schema and every schema is used" {
            val names =
                document
                    .getValue("components")
                    .jsonObject
                    .getValue("schemas")
                    .jsonObject.keys
            val targets = document.refs().map { it.removePrefix("#/components/schemas/") }.toSet()
            targets.forEach { (it in names) shouldBe true }
            names.filter { it !in targets } shouldBe emptyList()
        }

        "only the endpoints that need a key ask for one" {
            val open = ENDPOINTS.filter { it.auth == Auth.NONE }.map { it.operationId }
            open shouldContainExactlyInAnyOrder
                listOf("getHealth", "receiveGoogleNotification", "receiveAppleNotification")
        }

        "every request example is accepted by the document" {
            ENDPOINTS.filter { it.request?.example != null }.forEach { endpoint ->
                val report = validator.validateRequest(requestOf(endpoint, endpoint.request?.example))
                report.messages.map { it.message } shouldBe emptyList()
            }
        }

        "every response example is accepted by the document" {
            ENDPOINTS.forEach { endpoint ->
                endpoint.responses.forEach { response ->
                    val report = endpoint.validateResponse(response.status, response.body?.example)
                    report.messages.map { it.message } shouldBe emptyList()
                }
            }
        }

        "the document is strict where it must be: it rejects what the contract forbids" {
            val getSubscriber = ENDPOINTS.first { it.operationId == "getSubscriber" }
            val valid = Examples.customerInfo.jsonObject

            fun hasErrors(body: JsonObject) = getSubscriber.validateResponse(200, body).hasErrors()

            withClue("the valid example") { hasErrors(valid) shouldBe false }
            withClue("a missing required field") {
                hasErrors(JsonObject(valid.filterKeys { it != "app_user_id" })) shouldBe
                    true
            }
            withClue("a wrong type") {
                hasErrors(JsonObject(valid + ("active_subscriptions" to JsonPrimitive("nope")))) shouldBe
                    true
            }
        }

        "no schema forbids extra properties, so a newer backend stays compatible with older clients" {
            // The Atlassian validator itself treats a missing `additionalProperties` as "not allowed", so the
            // property is checked on the document, not through the validator.
            val schemas =
                document
                    .getValue("components")
                    .jsonObject
                    .getValue("schemas")
                    .jsonObject
            schemas.filterValues { it.jsonObject["additionalProperties"] == JsonPrimitive(false) }.keys shouldBe
                emptySet()
        }

        "a null expiry is valid but a missing one is not" {
            val getSubscriber = ENDPOINTS.first { it.operationId == "getSubscriber" }
            val entitlement =
                Examples.customerInfo.jsonObject
                    .getValue(
                        "entitlements",
                    ).jsonObject
                    .getValue("premium")
                    .jsonObject

            fun withEntitlement(value: JsonObject) =
                JsonObject(
                    Examples.customerInfo.jsonObject + ("entitlements" to buildJsonObject { put("premium", value) }),
                )

            val noExpiry = JsonObject(entitlement + ("expires_at" to kotlinx.serialization.json.JsonNull))
            getSubscriber.validateResponse(200, withEntitlement(noExpiry)).hasErrors() shouldBe false
            getSubscriber
                .validateResponse(
                    200,
                    withEntitlement(
                        JsonObject(
                            entitlement.filterKeys {
                                it != "expires_at"
                            },
                        ),
                    ),
                ).hasErrors() shouldBe
                true
        }

        "a receipt with an unknown store or a missing field is rejected" {
            val postReceipt = ENDPOINTS.first { it.operationId == "postReceipt" }
            val unknownStore =
                buildJsonObject {
                    put("store", "windows_store")
                    put("product_id", "x")
                }
            val missingToken =
                buildJsonObject {
                    put("store", "play_store")
                    put("package_name", "com.acme.app")
                    put("product_id", "premium_monthly")
                }
            validator.validateRequest(requestOf(postReceipt, unknownStore)).hasErrors() shouldBe true
            validator.validateRequest(requestOf(postReceipt, missingToken)).hasErrors() shouldBe true
            validator.validateRequest(requestOf(postReceipt, Examples.appStoreReceipt)).hasErrors() shouldBe false
        }

        "the committed docs/api/openapi.json is what the models and endpoints produce today" {
            val committed = File("../docs/api/openapi.json").readText()
            committed shouldBe spec
        }
    })
