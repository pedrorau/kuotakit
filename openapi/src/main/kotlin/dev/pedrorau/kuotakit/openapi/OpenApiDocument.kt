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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The OpenAPI 3.1 document of the KuotaKit API, built from [ENDPOINTS] and the models in [MODELS]. */
object OpenApiDocument {
    /** The version of this API description. Bumped with the releases (packages share `0.x.y`, ADR 0001). */
    const val VERSION = "0.1.0-draft"

    private const val JSON = "application/json"

    fun build(): JsonObject {
        val schemas = SchemaBuilder(MODELS)
        val paths = linkedMapOf<String, LinkedHashMap<String, JsonElement>>()
        ENDPOINTS.forEach { paths.getOrPut(it.path) { linkedMapOf() }[it.method.lowercase()] = operation(it, schemas) }
        return buildJsonObject {
            put("openapi", "3.1.0")
            putJsonObject("info") {
                put("title", "KuotaKit API")
                put("version", VERSION)
                put(
                    "description",
                    "The HTTP API of a KuotaKit backend. Generated from the `contracts` module; " +
                        "any backend that implements it and passes the conformance suite is a valid KuotaKit backend.",
                )
                putJsonObject("license") {
                    put("name", "Apache-2.0")
                    put("identifier", "Apache-2.0")
                }
            }
            putJsonArray(
                "tags",
            ) { ENDPOINTS.map { it.tag }.distinct().forEach { add(buildJsonObject { put("name", it) }) } }
            put("paths", JsonObject(paths.mapValues { JsonObject(it.value) }))
            putJsonObject("components") {
                putJsonObject("securitySchemes") {
                    putJsonObject("bearerAuth") {
                        put("type", "http")
                        put("scheme", "bearer")
                        put(
                            "description",
                            "`Authorization: Bearer <key>`. An app key (`pk_`) lives inside the mobile app and " +
                                "can read offerings, read its own subscriber and submit purchases. A secret key " +
                                "(`sk_`) stays on the developer's servers and can do everything.",
                        )
                    }
                }
                put("schemas", schemas.components())
            }
        }
    }

    private fun operation(
        endpoint: Endpoint,
        schemas: SchemaBuilder,
    ): JsonObject =
        buildJsonObject {
            putJsonArray("tags") { add(JsonPrimitive(endpoint.tag)) }
            put("summary", endpoint.summary)
            put("description", endpoint.description)
            put("operationId", endpoint.operationId)
            if (endpoint.pathParameters.isNotEmpty()) {
                putJsonArray("parameters") {
                    endpoint.pathParameters.forEach { (name, parameter) ->
                        add(
                            buildJsonObject {
                                put("name", name)
                                put("in", "path")
                                put("required", true)
                                put("description", parameter.description)
                                putJsonObject("schema") {
                                    put("type", "string")
                                    parameter.pattern?.let { put("pattern", it) }
                                }
                            },
                        )
                    }
                }
            }
            putJsonArray("security") {
                if (endpoint.auth == Auth.API_KEY) add(buildJsonObject { putJsonArray("bearerAuth") {} })
            }
            endpoint.request?.let { body ->
                putJsonObject("requestBody") {
                    put("required", true)
                    put("content", content(body, schemas))
                }
            }
            putJsonObject("responses") {
                endpoint.responses.forEach { response ->
                    putJsonObject(response.status.toString()) {
                        put("description", response.description)
                        response.body?.let { put("content", content(it, schemas)) }
                    }
                }
            }
        }

    private fun content(
        body: Body,
        schemas: SchemaBuilder,
    ): JsonObject =
        buildJsonObject {
            putJsonObject(JSON) {
                put("schema", body.schema(schemas))
                body.example?.let { put("example", it) }
            }
        }

    /** The document as pretty-printed JSON with a stable layout, ready to be committed and diffed. */
    @OptIn(ExperimentalSerializationApi::class)
    fun render(): String {
        val pretty =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }
        return pretty.encodeToString(JsonElement.serializer(), build()) + "\n"
    }
}
