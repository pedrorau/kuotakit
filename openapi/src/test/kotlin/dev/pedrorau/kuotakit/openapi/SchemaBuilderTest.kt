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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

@Serializable
enum class Color {
    @SerialName("red")
    RED,

    @SerialName("dark_blue")
    DARK_BLUE,
}

@Serializable
data class Inner(
    val id: Long,
)

@Serializable
data class Everything(
    val text: String,
    val flag: Boolean,
    val count: Int,
    val big: Long,
    val ratio: Double,
    @SerialName("when") val at: Instant,
    @SerialName("maybe_when") val maybeAt: Instant?,
    val maybe: String?,
    val withDefault: String = "x",
    val color: Color,
    val inner: Inner,
    val maybeInner: Inner?,
    val tags: List<String>,
    val byName: Map<String, Inner>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Shape

@Serializable
@SerialName("circle")
data class Circle(
    val radius: Int,
) : Shape

@Serializable
@SerialName("square")
data class Square(
    val side: Int,
) : Shape

@Serializable
sealed interface Plain

@Serializable
data class Leaf(
    val v: Int,
) : Plain

@Serializable
data class Node(
    val next: Node?,
)

@JvmInline
@Serializable
value class UserId(
    val value: String,
)

@Serializable
data class WithValueClass(
    val id: UserId,
    val maybeId: UserId?,
)

@Serializable
data class Box<T>(
    val v: T,
)

@Serializable
data class TwoBoxes(
    val x: Box<Int>,
    val y: Box<String>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Clashing

@Serializable
@SerialName("a")
data class ClashingA(
    val kind: String,
) : Clashing

private const val REF = "\$ref"

private fun JsonObject.obj(key: String) = getValue(key) as JsonObject

private fun components(vararg models: Pair<String, KSerializer<*>>) = SchemaBuilder(models.toList()).components()

@OptIn(InternalSerializationApi::class)
class SchemaBuilderTest :
    StringSpec({
        val everything = components("Everything" to Everything.serializer()).obj("Everything")
        val props = everything.obj("properties")

        "primitives map to OpenAPI types and formats" {
            props.obj("text").toString() shouldBe """{"type":"string"}"""
            props.obj("flag").toString() shouldBe """{"type":"boolean"}"""
            props.obj("count").toString() shouldBe """{"type":"integer","format":"int32"}"""
            props.obj("big").toString() shouldBe """{"type":"integer","format":"int64"}"""
            props.obj("ratio").toString() shouldBe """{"type":"number","format":"double"}"""
        }

        "an Instant is a date-time string" {
            props.obj("when").toString() shouldBe """{"type":"string","format":"date-time"}"""
        }

        "a nullable primitive allows null and a nullable Instant keeps its format" {
            props.obj("maybe").toString() shouldBe """{"type":["string","null"]}"""
            props.obj("maybe_when").toString() shouldBe """{"type":["string","null"],"format":"date-time"}"""
        }

        "a nullable model is a oneOf with null" {
            props.obj("maybeInner").toString() shouldBe
                """{"oneOf":[{"$REF":"#/components/schemas/Inner"},{"type":"null"}]}"""
        }

        "a nullable property without a default is still required, one with a default is optional" {
            val required = everything.getValue("required").toString()
            required.contains("\"maybe\"") shouldBe true
            required.contains("withDefault") shouldBe false
        }

        "enums list the serial names and are referenced" {
            props.obj("color").toString() shouldBe """{"$REF":"#/components/schemas/Color"}"""
            components("Everything" to Everything.serializer()).obj("Color").toString() shouldBe
                """{"type":"string","enum":["red","dark_blue"]}"""
        }

        "lists and maps are inlined with their element schemas" {
            props.obj("tags").toString() shouldBe """{"type":"array","items":{"type":"string"}}"""
            props.obj("byName").toString() shouldBe
                """{"type":"object","additionalProperties":{"$REF":"#/components/schemas/Inner"}}"""
        }

        "objects do not forbid extra properties, so newer backends stay compatible" {
            everything.containsKey("additionalProperties") shouldBe false
        }

        "a sealed hierarchy is a oneOf with a discriminator and a mapping" {
            val all =
                components(
                    "Shape" to Shape.serializer(),
                    "Circle" to Circle.serializer(),
                    "Square" to Square.serializer(),
                )
            all.obj("Shape").toString() shouldBe
                """{"oneOf":[{"$REF":"#/components/schemas/Circle"},{"$REF":"#/components/schemas/Square"}],""" +
                """"discriminator":{"propertyName":"kind","mapping":{"circle":"#/components/schemas/Circle",""" +
                """"square":"#/components/schemas/Square"}}}"""
            all.obj("Circle").toString() shouldBe
                """{"type":"object","properties":{"kind":{"type":"string","enum":["circle"]},""" +
                """"radius":{"type":"integer","format":"int32"}},"required":["kind","radius"]}"""
        }

        "a sealed hierarchy without @JsonClassDiscriminator uses `type`, like kotlinx.serialization" {
            val plain = components("Plain" to Plain.serializer()).obj("Plain")
            plain.obj("discriminator").getValue("propertyName").toString() shouldBe "\"type\""
        }

        "a recursive model is described once and does not loop" {
            val node = components("Node" to Node.serializer())
            node.keys shouldBe setOf("Node")
            node
                .obj("Node")
                .obj("properties")
                .obj("next")
                .toString() shouldBe
                """{"oneOf":[{"$REF":"#/components/schemas/Node"},{"type":"null"}]}"""
        }

        "an unregistered model is named after its serial name" {
            components("Everything" to Everything.serializer()).keys shouldBe setOf("Everything", "Color", "Inner")
        }

        "two models cannot share a name or a serial name" {
            shouldThrow<IllegalArgumentException> { components("A" to Inner.serializer(), "B" to Inner.serializer()) }
        }

        "a value class is its single property, not an object" {
            val c = components("WithValueClass" to WithValueClass.serializer())
            c.keys shouldBe setOf("WithValueClass")
            val props = c.obj("WithValueClass").obj("properties")
            props.obj("id").toString() shouldBe """{"type":"string"}"""
            props.obj("maybeId").toString() shouldBe """{"type":["string","null"]}"""
        }

        "generic models with different type arguments are rejected instead of merged" {
            shouldThrow<IllegalStateException> { components("TwoBoxes" to TwoBoxes.serializer()) }
        }

        "two schemas cannot have the same name" {
            shouldThrow<IllegalArgumentException> { components("X" to Inner.serializer(), "X" to Leaf.serializer()) }
        }

        "components() is a snapshot: schemas found later do not appear in an earlier result" {
            val builder = SchemaBuilder(listOf("Inner" to Inner.serializer()))
            val before = builder.components()
            builder.schemaOf(Everything.serializer().descriptor)
            before.keys shouldBe setOf("Inner")
            builder.components().keys shouldBe setOf("Inner", "Everything", "Color")
        }

        "a subtype with a property named like the discriminator is rejected" {
            shouldThrow<IllegalArgumentException> {
                components("Clashing" to Clashing.serializer(), "ClashingA" to ClashingA.serializer())
            }
        }

        "a descriptor the walker does not know fails loudly" {
            shouldThrow<UnsupportedOperationException> {
                SchemaBuilder(emptyList()).schemaOf(buildSerialDescriptor("Contextual", SerialKind.CONTEXTUAL))
            }
        }
    })
