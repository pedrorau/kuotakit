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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun type(
    name: String,
    format: String? = null,
): JsonObject =
    buildJsonObject {
        put("type", name)
        if (format != null) put("format", format)
    }

/**
 * Turns the `@Serializable` models of `contracts` into OpenAPI 3.1 schemas by walking their `SerialDescriptor`s, so
 * the models stay the single source of truth (ADR 0007, ADR 0012).
 *
 * Composite models (classes, enums, sealed hierarchies) become named entries of `components/schemas` and are
 * referenced with `$ref`; primitives, lists and maps are inlined. A model that is not registered gets the last part
 * of its serial name. Anything this walker does not know (for example contextual serializers) fails loudly instead
 * of producing a vague schema.
 */
@OptIn(ExperimentalSerializationApi::class)
class SchemaBuilder(
    models: List<Pair<String, KSerializer<*>>>,
) {
    private val namesBySerialName = models.associate { (name, serializer) -> serializer.descriptor.serialName to name }
    private val components = linkedMapOf<String, JsonObject>()
    private val pending = ArrayDeque<Pair<String, SerialDescriptor>>()
    private val seen = mutableMapOf<String, SerialDescriptor>()

    init {
        require(models.map { it.first }.toSet().size == models.size) { "Two models share a schema name" }
        require(namesBySerialName.size == models.size) { "Two models share a serial name" }
        models.forEach { (_, serializer) -> reference(serializer.descriptor) }
    }

    /** The schema of a property or body of this type: a `$ref` for named models, inline for the rest. */
    fun schemaOf(descriptor: SerialDescriptor): JsonObject = schema(descriptor)

    /** All the named schemas found so far, in discovery order (a snapshot: later calls do not change it). */
    fun components(): JsonObject {
        while (pending.isNotEmpty()) {
            val (name, descriptor) = pending.removeFirst()
            components[name] = definition(descriptor)
        }
        return JsonObject(LinkedHashMap(components))
    }

    private fun schema(descriptor: SerialDescriptor): JsonObject {
        val base = nonNullSchema(descriptor)
        return if (descriptor.isNullable) nullable(base) else base
    }

    private fun nullable(schema: JsonObject): JsonObject {
        if ("\$ref" in
            schema
        ) {
            return buildJsonObject {
                putJsonArray("oneOf") {
                    add(schema)
                    add(buildJsonObject { put("type", "null") })
                }
            }
        }
        val type = schema.getValue("type") as JsonPrimitive
        return JsonObject(
            schema + (
                "type" to
                    buildJsonArray {
                        add(type)
                        add(JsonPrimitive("null"))
                    }
            ),
        )
    }

    private fun nonNullSchema(descriptor: SerialDescriptor): JsonObject =
        // A value class is written as its single property, not as an object.
        if (descriptor.isInline) schema(descriptor.getElementDescriptor(0)) else kindSchema(descriptor)

    private fun kindSchema(descriptor: SerialDescriptor): JsonObject =
        when (val kind = descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> {
                stringSchema(descriptor)
            }

            PrimitiveKind.BOOLEAN -> {
                type("boolean")
            }

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT -> {
                type("integer", "int32")
            }

            PrimitiveKind.LONG -> {
                type("integer", "int64")
            }

            PrimitiveKind.FLOAT -> {
                type("number", "float")
            }

            PrimitiveKind.DOUBLE -> {
                type("number", "double")
            }

            StructureKind.LIST -> {
                buildJsonObject {
                    put("type", "array")
                    put("items", schema(descriptor.getElementDescriptor(0)))
                }
            }

            StructureKind.MAP -> {
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", schema(descriptor.getElementDescriptor(1)))
                }
            }

            SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> {
                reference(descriptor)
            }

            else -> {
                throw UnsupportedOperationException("No schema for ${descriptor.serialName} ($kind)")
            }
        }

    private fun stringSchema(descriptor: SerialDescriptor): JsonObject =
        // Instants travel as ISO 8601 strings (kotlinx.serialization's own serializer for kotlin.time.Instant).
        if (descriptor.serialName.removeSuffix("?") == INSTANT) type("string", "date-time") else type("string")

    private fun reference(descriptor: SerialDescriptor): JsonObject {
        val serialName = descriptor.serialName.removeSuffix("?")
        val plain = if (descriptor.isNullable) descriptor.nonNullOriginal else descriptor
        // Generic types share one serial name for every type argument (`Box<A>` and `Box<B>` are both "Box").
        check(seen.getOrPut(serialName) { plain } == plain) {
            "Two different types share the serial name $serialName (generic types are not supported)"
        }
        val name = namesBySerialName[serialName] ?: serialName.substringAfterLast('.')
        if (name !in components) {
            components[name] = JsonObject(emptyMap()) // reserved now so a recursive model is not walked twice
            pending.addLast(name to descriptor)
        }
        return buildJsonObject { put("\$ref", "#/components/schemas/$name") }
    }

    private fun definition(descriptor: SerialDescriptor): JsonObject =
        when (descriptor.kind) {
            SerialKind.ENUM -> {
                buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { descriptor.elementNames.forEach { add(JsonPrimitive(it)) } }
                }
            }

            PolymorphicKind.SEALED -> {
                sealed(descriptor)
            }

            else -> {
                objectSchema(descriptor)
            }
        }

    private fun objectSchema(
        descriptor: SerialDescriptor,
        discriminator: Pair<String, String>? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            val required = mutableListOf<String>()
            require(discriminator == null || discriminator.first !in descriptor.elementNames) {
                "${descriptor.serialName} has a property named like its discriminator (${discriminator?.first})"
            }
            put(
                "properties",
                buildJsonObject {
                    if (discriminator != null) {
                        put(
                            discriminator.first,
                            buildJsonObject {
                                put("type", "string")
                                putJsonArray("enum") { add(JsonPrimitive(discriminator.second)) }
                            },
                        )
                        required += discriminator.first
                    }
                    descriptor.elementNames.forEachIndexed { index, name ->
                        put(name, schema(descriptor.getElementDescriptor(index)))
                        // A nullable property without a default is still required: `null` is sent explicitly.
                        if (!descriptor.isElementOptional(index)) required += name
                    }
                },
            )
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }

    private fun sealed(descriptor: SerialDescriptor): JsonObject {
        val property =
            descriptor.annotations
                .filterIsInstance<JsonClassDiscriminator>()
                .firstOrNull()
                ?.discriminator ?: "type"
        val subtypes = descriptor.getElementDescriptor(1).elementDescriptors.toList()
        val mapping = linkedMapOf<String, JsonElement>()
        val refs =
            subtypes.map { subtype ->
                val ref = reference(subtype).getValue("\$ref") as JsonPrimitive
                val name = ref.content.substringAfterLast('/')
                // The subtype's schema is the object of its own fields plus the field that tells the subtypes apart.
                pending.removeAll { it.first == name }
                components[name] = objectSchema(subtype, property to subtype.serialName)
                mapping[subtype.serialName] = ref
                buildJsonObject { put("\$ref", ref.content) }
            }
        return buildJsonObject {
            put("oneOf", JsonArray(refs))
            put(
                "discriminator",
                buildJsonObject {
                    put("propertyName", property)
                    put("mapping", JsonObject(mapping))
                },
            )
        }
    }

    private companion object {
        const val INSTANT = "kotlin.time.Instant"
    }
}

/** A `null` literal, for examples that must show an explicit `null`. */
val NULL: JsonElement = JsonNull
