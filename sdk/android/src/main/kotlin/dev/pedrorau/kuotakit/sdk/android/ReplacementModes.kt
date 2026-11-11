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

package dev.pedrorau.kuotakit.sdk.android

import com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.ReplacementMode
import dev.pedrorau.kuotakit.sdk.ReplacementMode as SdkMode

/** Google's constant for each replacement mode of the SDK (docs: Play Billing, subscriptions, replacement modes). */
internal fun replacementModeOf(mode: SdkMode): Int =
    when (mode) {
        SdkMode.CHARGE_PRORATED_PRICE -> ReplacementMode.CHARGE_PRORATED_PRICE
        SdkMode.WITH_TIME_PRORATION -> ReplacementMode.WITH_TIME_PRORATION
        SdkMode.CHARGE_FULL_PRICE -> ReplacementMode.CHARGE_FULL_PRICE
        SdkMode.WITHOUT_PRORATION -> ReplacementMode.WITHOUT_PRORATION
        SdkMode.DEFERRED -> ReplacementMode.DEFERRED
    }
