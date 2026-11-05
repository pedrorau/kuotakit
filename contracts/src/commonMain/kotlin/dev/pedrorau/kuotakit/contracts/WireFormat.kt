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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The JSON configuration every KuotaKit backend and SDK uses for the models of this module. The wire format
 * (docs/api-spec.md) depends on these settings, so they live next to the models:
 * - `ignoreUnknownKeys`: a newer backend can add fields without breaking older clients;
 * - an enum value the reader does not know decodes as the `UNKNOWN` member of the enum (each enum serializes itself);
 * - `explicitNulls` and `encodeDefaults`: a nullable field is always written, with `null` when it does not apply.
 *
 * A receipt must be encoded as a [ReceiptRequest] (`kuotaKitJson.encodeToString(ReceiptRequest.serializer(), value)`
 * or a value typed as the interface); encoding it with the serializer of its own class leaves out the `store` field.
 */
@OptIn(ExperimentalSerializationApi::class)
public val kuotaKitJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = true
        encodeDefaults = true
    }
