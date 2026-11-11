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

package dev.pedrorau.kuotakit.sdk.android

import android.content.Context
import dev.pedrorau.kuotakit.sdk.KeyValueStore

/**
 * Keeps the anonymous app user id in the app's private preferences. The id is a secret of the user (ADR 0009): an app
 * that backs up its preferences should leave this file out if it does not want the id restored on another device.
 */
public class SharedPreferencesStore(
    context: Context,
) : KeyValueStore {
    private val preferences = context.applicationContext.getSharedPreferences("kuotakit", Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(
        key: String,
        value: String,
    ) {
        // commit, not apply: the id must be on disk before a purchase is made under it.
        preferences.edit().putString(key, value).commit()
    }
}
