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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pedrorau.kuotakit.sandbox.ScreenState

/** The frame of every screen: a scrolling column with the same padding. */
@Composable
internal fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

@Composable
internal fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

@Composable
internal fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

/** Shows the three states of a screen; [ready] draws the answer. */
@Composable
internal fun <T> StateView(
    state: ScreenState<T>,
    ready: @Composable (T) -> Unit,
) {
    when (state) {
        ScreenState.Loading -> CircularProgressIndicator()
        is ScreenState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        is ScreenState.Ready -> ready(state.data)
    }
}
