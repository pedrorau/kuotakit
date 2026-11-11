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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }
}

class AppUserIdTest {
    @Test
    fun theIdIsMadeOnceAndKept() {
        val store = MemoryStore()
        val first = appUserIdIn(store)
        assertEquals(first, appUserIdIn(store))
        assertEquals(first, store.values.values.single())
    }

    @Test
    fun theIdFitsWhatTheBackendAccepts() {
        val id = appUserIdIn(MemoryStore())
        assertTrue(Regex(APP_USER_ID_PATTERN).matches(id), id)
        assertTrue(id.startsWith("\$anon_"))
        assertEquals(6 + 32, id.length)
    }

    @Test
    fun aCleanStoreGetsAnotherId() {
        assertNotEquals(appUserIdIn(MemoryStore()), appUserIdIn(MemoryStore()))
    }

    @Test
    fun aStoredIdThatIsNotValidIsReplaced() {
        val store = MemoryStore()
        store.put("kuotakit.app_user_id", "has a space")
        val id = appUserIdIn(store)
        assertTrue(Regex(APP_USER_ID_PATTERN).matches(id))
        assertEquals(id, store.get("kuotakit.app_user_id"))
    }
}
