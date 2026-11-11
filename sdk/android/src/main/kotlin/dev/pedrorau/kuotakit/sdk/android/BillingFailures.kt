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

import com.android.billingclient.api.BillingClient.BillingResponseCode
import dev.pedrorau.kuotakit.sdk.KuotaKitException

/** What a response code of Play Billing that is not `OK` or `USER_CANCELED` means for the app. */
internal fun failureOf(
    code: Int,
    debugMessage: String,
): KuotaKitException =
    when (code) {
        BillingResponseCode.BILLING_UNAVAILABLE,
        BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.NETWORK_ERROR,
        BillingResponseCode.FEATURE_NOT_SUPPORTED,
        -> {
            KuotaKitException.StoreUnavailable("Google Play billing is not available ($code): $debugMessage")
        }

        BillingResponseCode.ITEM_UNAVAILABLE -> {
            KuotaKitException.InvalidRequest("Google Play does not offer this product ($code): $debugMessage")
        }

        BillingResponseCode.ITEM_ALREADY_OWNED -> {
            KuotaKitException.InvalidRequest(
                "The user already owns this product ($code): $debugMessage. If the backend never confirmed it, " +
                    "it cannot be bought again until Google refunds it (restore comes in a later version)",
            )
        }

        else -> {
            KuotaKitException.Unexpected("Google Play billing failed ($code): $debugMessage")
        }
    }
