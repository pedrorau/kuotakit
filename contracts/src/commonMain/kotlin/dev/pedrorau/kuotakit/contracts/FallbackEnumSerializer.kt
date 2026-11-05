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

package dev.pedrorau.kuotakit.contracts

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** What an unknown value is written as when a model is serialized again (for example the SDK's offline cache). */
private const val UNKNOWN_WIRE_NAME = "unknown"

/**
 * Serializer of an enum of the API that tolerates values it does not know: a newer backend can add one, and an older
 * client decodes it as [fallback] instead of failing to read the whole body.
 *
 * The descriptor lists only the values a backend sends, which is what the OpenAPI document shows; the fallback is a
 * client-side member that is never part of the contract.
 */
@OptIn(InternalSerializationApi::class)
internal open class FallbackEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val wireNames: Map<E, String>,
    private val fallback: E,
) : KSerializer<E> {
    private val byWireName = wireNames.entries.associate { (value, name) -> name to value }

    override val descriptor: SerialDescriptor =
        buildSerialDescriptor(serialName, SerialKind.ENUM) {
            wireNames.values.forEach { name ->
                element(name, buildSerialDescriptor("$serialName.$name", StructureKind.OBJECT))
            }
        }

    override fun deserialize(decoder: Decoder): E = byWireName[decoder.decodeString()] ?: fallback

    override fun serialize(
        encoder: Encoder,
        value: E,
    ) {
        encoder.encodeString(wireNames[value] ?: UNKNOWN_WIRE_NAME)
    }
}
