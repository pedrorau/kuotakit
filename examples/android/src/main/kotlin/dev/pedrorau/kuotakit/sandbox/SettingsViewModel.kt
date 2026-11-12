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

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Settings screen is always in one of these. */
internal sealed interface SettingsState {
    /** The user is typing; [problem] is what was wrong with the last attempt to save, if anything. */
    data class Editing(
        val problem: String? = null,
    ) : SettingsState

    /** Saved: the app has to restart so the SDK is configured again from scratch (it is configured once). */
    data object Saved : SettingsState
}

/** Checks and saves the backend address and the public key. */
internal class SettingsViewModel(
    private val save: (Validation.Valid) -> Boolean,
    initialBaseUrl: String,
    initialApiKey: String,
) : ViewModel() {
    private val mutableBaseUrl = MutableStateFlow(initialBaseUrl)
    val baseUrl: StateFlow<String> = mutableBaseUrl.asStateFlow()

    private val mutableApiKey = MutableStateFlow(initialApiKey)
    val apiKey: StateFlow<String> = mutableApiKey.asStateFlow()

    private val mutableState = MutableStateFlow<SettingsState>(SettingsState.Editing())
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    fun baseUrlChanged(value: String) {
        mutableBaseUrl.value = value
        mutableState.value = SettingsState.Editing()
    }

    fun apiKeyChanged(value: String) {
        mutableApiKey.value = value
        mutableState.value = SettingsState.Editing()
    }

    fun save() {
        mutableState.value =
            when (val result = SettingsValidation.validate(mutableBaseUrl.value, mutableApiKey.value)) {
                is Validation.Invalid -> {
                    SettingsState.Editing(result.problem)
                }

                is Validation.Valid -> {
                    if (save(result)) SettingsState.Saved else SettingsState.Editing("The settings could not be saved.")
                }
            }
    }
}
