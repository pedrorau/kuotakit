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
import androidx.lifecycle.viewModelScope
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.Entitlement
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.sdk.CachedCustomerInfo
import dev.pedrorau.kuotakit.sdk.PlanChange
import dev.pedrorau.kuotakit.sdk.ReplacementMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One entitlement as the backend answered it. */
internal data class EntitlementRow(
    val id: String,
    val isActive: Boolean,
    val productId: String,
    val periodType: String,
    val expiresAt: String?,
    val willRenew: Boolean,
    val billingIssue: Boolean,
)

/** A change of plan the user can ask for: [target] replaces [replacing], in the given [mode]. */
internal data class SwitchOption(
    val target: OfferingPackage,
    val replacing: OfferingPackage,
    val mode: ReplacementMode,
) {
    val label: String
        get() =
            when (mode) {
                ReplacementMode.DEFERRED -> "Switch to ${target.id} when the current plan ends"
                else -> "Switch to ${target.id} now (prorated)"
            }
}

/**
 * Everything the Plan screen shows about access: only what the backend answered. [stale] says that the last attempt
 * to ask the backend again failed, so this may be out of date.
 */
internal data class PlanView(
    val entitlements: List<EntitlementRow>,
    val activeSubscriptions: List<String>,
    val switches: List<SwitchOption>,
    val stale: Boolean = false,
)

/**
 * What the user has now. The only source is the answer of the backend, as the SDK holds it in `customerInfo` (every
 * purchase, restore and refresh updates it, newest answer last); a failure leaves what was shown and marks it as
 * possibly out of date. The last known answer kept on the device is offered separately, labelled as not being proof
 * of access. Calls do not overlap: while one runs, another is skipped (a restore when the app comes to the foreground
 * must not run in the middle of a purchase).
 */
internal class PlanViewModel(
    private val gateway: SubscriptionsGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ScreenState<PlanView>>(ScreenState.Loading)
    val state: StateFlow<ScreenState<PlanView>> = mutableState.asStateFlow()

    /** The last answer kept on the device and when it came. Never turned into access by the app. */
    val lastKnown: StateFlow<CachedCustomerInfo?> get() = gateway.lastKnown

    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)

    /** A call the user asked for is running. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private var packages: List<OfferingPackage> = emptyList()
    private var running = false

    init {
        viewModelScope.launch { gateway.customerInfo.collect { info -> if (info != null) show(info) } }
        refresh()
    }

    fun refresh() =
        exclusive {
            packages = (guarded { gateway.offerings().currentPackages() } as? Guarded.Done)?.value.orEmpty()
            when (val result = guarded { gateway.refresh() }) {
                is Guarded.Done -> show(result.value)
                is Guarded.Failed -> couldNotRefresh(result.message)
            }
        }

    /** The app came to the foreground: sync with Google Play quietly (Google's advice). A failure changes nothing. */
    fun onForeground() = exclusive { guarded { gateway.restore() } }

    fun restore() =
        userAction {
            when (val result = guarded { gateway.restore() }) {
                is Guarded.Done -> {
                    with(result.value) {
                        "Restore finished: $submitted sent, $refused refused, $unattributed not attributable."
                    }
                }

                is Guarded.Failed -> {
                    result.message
                }
            }
        }

    fun switchPlan(option: SwitchOption) =
        userAction {
            when (val result = guarded { gateway.purchase(option.target, PlanChange(option.replacing, option.mode)) }) {
                is Guarded.Done -> result.value.words()
                is Guarded.Failed -> result.message
            }
        }

    private fun userAction(block: suspend () -> String) =
        exclusive {
            mutableBusy.value = true
            mutableNotice.value = null
            try {
                mutableNotice.value = block()
            } finally {
                mutableBusy.value = false
            }
        }

    private fun exclusive(block: suspend () -> Unit) {
        if (running) return
        running = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                running = false
            }
        }
    }

    private fun couldNotRefresh(message: String) {
        mutableState.value =
            when (val current = mutableState.value) {
                is ScreenState.Ready -> ScreenState.Ready(current.data.copy(stale = true))
                else -> ScreenState.Failed(message)
            }
    }

    private fun show(info: CustomerInfo) {
        val rows = info.entitlements.map { (id, e) -> e.toRow(id) }
        mutableState.value = ScreenState.Ready(PlanView(rows, info.activeSubscriptions, switchesFor(rows)))
    }

    private fun switchesFor(rows: List<EntitlementRow>): List<SwitchOption> {
        val owned = rows.firstOrNull { it.isActive }?.productId
        val replacing = packages.firstOrNull { it.products.playStore == owned }
        return if (owned == null || replacing == null) {
            emptyList()
        } else {
            packages
                .filter { it.products.playStore != null && it.id != replacing.id }
                .flatMap { target ->
                    MODES.map { SwitchOption(target, replacing, it) }
                }
        }
    }

    private fun Entitlement.toRow(id: String) =
        EntitlementRow(
            id = id,
            isActive = isActive,
            productId = productId,
            periodType = periodType.name.lowercase(),
            expiresAt = expiresAt?.toString(),
            willRenew = willRenew,
            billingIssue = billingIssueDetectedAt != null,
        )

    private companion object {
        val MODES = listOf(ReplacementMode.CHARGE_PRORATED_PRICE, ReplacementMode.DEFERRED)
    }
}
