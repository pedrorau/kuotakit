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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsViewModelTest {
    private val saved = mutableListOf<Validation.Valid>()

    private fun model(succeeds: Boolean = true) =
        SettingsViewModel({
            saved += it
            succeeds
        }, "https://host.example", "pk_0123456789abcdef")

    @Test
    fun `valid settings are saved, trimmed, and the app has to restart`() {
        val model = model()
        model.baseUrlChanged(" https://sandbox.example/ ")
        model.save()
        assertEquals(SettingsState.Saved, model.state.value)
        assertEquals(listOf(Validation.Valid("https://sandbox.example", "pk_0123456789abcdef")), saved)
    }

    @Test
    fun `a secret key is not saved and the problem is shown`() {
        val model = model()
        model.apiKeyChanged("sk_0123456789abcdef")
        model.save()
        val state = model.state.value as SettingsState.Editing
        assertTrue(state.problem.orEmpty().contains("secret key"))
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `an address that is not https is not saved`() {
        val model = model()
        model.baseUrlChanged("http://sandbox.example")
        model.save()
        assertTrue(model.state.value is SettingsState.Editing)
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `typing again clears the problem, and a write that fails is not reported as saved`() {
        val model = model(succeeds = false)
        model.save()
        assertEquals(SettingsState.Editing("The settings could not be saved."), model.state.value)
        model.apiKeyChanged("pk_other_key_value")
        assertEquals(SettingsState.Editing(), model.state.value)
    }
}
