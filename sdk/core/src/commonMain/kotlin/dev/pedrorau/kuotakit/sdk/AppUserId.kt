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

package dev.pedrorau.kuotakit.sdk

import dev.pedrorau.kuotakit.contracts.APP_USER_ID_PATTERN
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val KEY = "kuotakit.app_user_id"
private val VALID = Regex(APP_USER_ID_PATTERN)

/**
 * The anonymous app user id (ADR 0005): `$anon_` and a random UUID (122 random bits), made once and kept on the
 * device. It is the secret of the user (ADR 0009), so it is never logged. One that was stored but is not valid is
 * replaced.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun appUserIdIn(store: KeyValueStore): String {
    store.get(KEY)?.takeIf { VALID.matches(it) }?.let { return it }
    val created = "\$anon_" + Uuid.random().toHexString()
    store.put(KEY, created)
    return created
}
