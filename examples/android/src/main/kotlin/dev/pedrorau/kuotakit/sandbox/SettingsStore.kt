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

package dev.pedrorau.kuotakit.sandbox

import android.content.Context

/** The address and the key typed in Settings, kept in the app's private preferences; the build defaults otherwise. */
internal class SettingsStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("sandbox_settings", Context.MODE_PRIVATE)

    val baseUrl: String get() = preferences.getString(BASE_URL, null) ?: BuildConfig.DEFAULT_BASE_URL
    val apiKey: String get() = preferences.getString(API_KEY, null) ?: BuildConfig.DEFAULT_API_KEY

    /** Saved with `commit`: the app restarts right after, and an asynchronous write could be lost. */
    fun save(valid: Validation.Valid): Boolean =
        preferences
            .edit()
            .putString(BASE_URL, valid.baseUrl)
            .putString(API_KEY, valid.apiKey)
            .commit()

    private companion object {
        const val BASE_URL = "base_url"
        const val API_KEY = "api_key"
    }
}
