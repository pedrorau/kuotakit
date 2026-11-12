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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pedrorau.kuotakit.sandbox.OfferingsViewModel
import dev.pedrorau.kuotakit.sandbox.PlanViewModel
import dev.pedrorau.kuotakit.sandbox.SettingsStore
import dev.pedrorau.kuotakit.sandbox.SettingsViewModel
import dev.pedrorau.kuotakit.sandbox.SubscriptionsGateway

private enum class Tab(
    val title: String,
) {
    OFFERINGS("Offerings"),
    PLAN("Plan"),
    SETTINGS("Settings"),
}

/** The three screens. When the app has no usable address or key it opens on Settings. */
@Composable
internal fun SandboxApp(
    gateway: SubscriptionsGateway,
    settings: SettingsStore,
    configurationProblem: String?,
) {
    var tab by rememberSaveable { mutableStateOf(if (configurationProblem == null) Tab.OFFERINGS else Tab.SETTINGS) }
    val offerings = viewModel { OfferingsViewModel(gateway) }
    val plan = viewModel { PlanViewModel(gateway) }
    val settingsModel = viewModel { SettingsViewModel(settings::save, settings.baseUrl, settings.apiKey) }
    // Google's advice: sync with Google Play whenever the app comes to the foreground.
    // Not while a purchase is waiting: the Google Play sheet closing is also a return to the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (offerings.buying.value == null) plan.onForeground() }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.title) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.OFFERINGS -> OfferingsScreen(offerings)
                Tab.PLAN -> PlanScreen(plan)
                Tab.SETTINGS -> SettingsScreen(settingsModel, gateway.appUserId, configurationProblem)
            }
        }
    }
}
