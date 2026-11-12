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
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What can be bought: the packages of the current offering as the backend sells them. Buying changes nothing here: what
 * the user has now is read in [PlanViewModel] from what the backend answered.
 */
internal class OfferingsViewModel(
    private val gateway: SubscriptionsGateway,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ScreenState<List<OfferingPackage>>>(ScreenState.Loading)
    val state: StateFlow<ScreenState<List<OfferingPackage>>> = mutableState.asStateFlow()

    private val mutableBuying = MutableStateFlow<String?>(null)

    /** The id of the package being bought, or `null`. */
    val buying: StateFlow<String?> = mutableBuying.asStateFlow()

    private val mutableNotice = MutableStateFlow<String?>(null)

    /** The outcome of the last purchase, in words. */
    val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value =
                when (val result = guarded { gateway.offerings().currentPackages() }) {
                    is Guarded.Done -> ScreenState.Ready(result.value)
                    is Guarded.Failed -> ScreenState.Failed(result.message)
                }
        }
    }

    fun buy(pkg: OfferingPackage) {
        if (mutableBuying.value != null) return
        mutableBuying.value = pkg.id
        mutableNotice.value = null
        viewModelScope.launch {
            try {
                mutableNotice.value =
                    when (val result = guarded { gateway.purchase(pkg) }) {
                        is Guarded.Done -> result.value.words()
                        is Guarded.Failed -> result.message
                    }
            } finally {
                mutableBuying.value = null
            }
        }
    }
}
