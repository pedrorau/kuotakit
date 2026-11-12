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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pedrorau.kuotakit.sandbox.EntitlementRow
import dev.pedrorau.kuotakit.sandbox.PlanView
import dev.pedrorau.kuotakit.sandbox.PlanViewModel
import dev.pedrorau.kuotakit.sdk.CachedCustomerInfo

/** What the user has: only what the backend answered. The last known answer is below, labelled as not proof. */
@Composable
internal fun PlanScreen(model: PlanViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val notice by model.notice.collectAsStateWithLifecycle()
    val lastKnown by model.lastKnown.collectAsStateWithLifecycle()
    ScreenColumn {
        Heading("Plan")
        Note("Access comes only from the backend's answer. The app never decides it.")
        StateView(state) { plan -> PlanContent(plan, busy, model) }
        notice?.let { Note(it) }
        Button(onClick = model::restore, enabled = !busy) { Text("Restore purchases") }
        OutlinedButton(onClick = model::refresh) { Text("Refresh") }
        lastKnown?.let { LastKnown(it) }
    }
}

@Composable
private fun PlanContent(
    plan: PlanView,
    busy: Boolean,
    model: PlanViewModel,
) {
    if (plan.stale) {
        Text(
            "Could not ask the backend again: this may be out of date.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (plan.entitlements.isEmpty()) Note("No entitlements: the backend says this user has nothing.")
    plan.entitlements.forEach { EntitlementCard(it) }
    plan.switches.forEach { option ->
        OutlinedButton(onClick = { model.switchPlan(option) }, enabled = !busy) { Text(option.label) }
    }
}

@Composable
private fun EntitlementCard(row: EntitlementRow) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${row.id}: ${if (row.isActive) "active" else "not active"}")
            Text("Product ${row.productId} (${row.periodType})")
            Text(row.expiresAt?.let { "Until $it" } ?: "No end date")
            Text(if (row.willRenew) "Renews" else "Does not renew")
            if (row.billingIssue) Text("Google reported a payment problem.")
        }
    }
}

@Composable
private fun LastKnown(cached: CachedCustomerInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Last known answer (not proof of access)")
            Text("Received ${cached.receivedAt}. It can be old or edited: it is only what the backend said then.")
            Text(
                "Active then: ${cached.customerInfo.entitlements.filterValues {
                    it.isActive
                }.keys.joinToString().ifEmpty { "none" }}",
            )
        }
    }
}
