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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pedrorau.kuotakit.sandbox.OfferingsViewModel

/** What the backend sells. The SDK gives no Google Play price text yet, so none is shown. */
@Composable
internal fun OfferingsScreen(model: OfferingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val buying by model.buying.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    ScreenColumn {
        Heading("Offerings")
        Note("What the backend sells (the offering `current` of its entitlements.yaml). Test purchases only.")
        StateView(state) { packages ->
            packages.forEach { pkg ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(pkg.id)
                        Text("Product: ${pkg.products.playStore ?: "not sold on Google Play"}")
                        Button(
                            onClick = { model.buy(pkg) },
                            enabled = buying == null && pkg.products.playStore != null,
                        ) {
                            Text(if (buying == pkg.id) "Waiting for Google Play…" else "Buy")
                        }
                    }
                }
            }
        }
        notice?.let { Note(it) }
        OutlinedButton(onClick = model::load) { Text("Reload") }
    }
}
