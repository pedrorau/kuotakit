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

package dev.pedrorau.kuotakit.sandbox.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pedrorau.kuotakit.sandbox.BuildConfig
import dev.pedrorau.kuotakit.sandbox.SettingsState
import dev.pedrorau.kuotakit.sandbox.SettingsViewModel
import dev.pedrorau.kuotakit.sandbox.restartApp
import dev.pedrorau.kuotakit.sandbox.truncatedUserId

/** The backend address and the public key, typed at run time. A change restarts the app (the SDK is set up once). */
@Composable
internal fun SettingsScreen(
    model: SettingsViewModel,
    appUserId: String,
    configurationProblem: String?,
) {
    val baseUrl by model.baseUrl.collectAsStateWithLifecycle()
    val apiKey by model.apiKey.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var linkOpened by remember { mutableStateOf(true) }
    LaunchedEffect(state) { if (state == SettingsState.Saved) restartApp(context) }

    ScreenColumn {
        Heading("Settings")
        Note("The address of the backend and the PUBLIC key of the app (pk_...). Never a secret key (sk_...).")
        configurationProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = model::baseUrlChanged,
            label = { Text("Backend address (https://...)") },
            singleLine = true,
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = model::apiKeyChanged,
            label = { Text("Public key (pk_...)") },
            singleLine = true,
        )
        (state as? SettingsState.Editing)?.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state == SettingsState.Saved) Note("Saved. Restarting the app…")
        Button(onClick = model::save) { Text("Save and restart") }
        if (BuildConfig.PRIVACY_POLICY_URL.isNotEmpty()) {
            TextButton(
                onClick = { linkOpened = runCatching { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) }.isSuccess },
            ) { Text("Privacy policy") }
            if (!linkOpened) Note("Could not open the link. The privacy policy is at ${BuildConfig.PRIVACY_POLICY_URL}")
        }
        if (appUserId.isNotEmpty()) {
            Note(
                "Your anonymous user id (secret, shown in part): ${truncatedUserId(appUserId)}",
            )
        }
    }
}
