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
import kotlin.test.Test
import kotlin.test.assertIs

class BillingFailuresTest {
    @Test
    fun billingThatCannotBeUsedIsStoreUnavailable() {
        listOf(
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.NETWORK_ERROR,
            BillingResponseCode.FEATURE_NOT_SUPPORTED,
        ).forEach { assertIs<KuotaKitException.StoreUnavailable>(failureOf(it, "m"), "code $it") }
    }

    @Test
    fun aProductThatCannotBeBoughtIsAnInvalidRequest() {
        assertIs<KuotaKitException.InvalidRequest>(failureOf(BillingResponseCode.ITEM_UNAVAILABLE, "m"))
        assertIs<KuotaKitException.InvalidRequest>(failureOf(BillingResponseCode.ITEM_ALREADY_OWNED, "m"))
    }

    @Test
    fun anythingElseIsUnexpectedAndNeverSuccess() {
        assertIs<KuotaKitException.Unexpected>(failureOf(BillingResponseCode.DEVELOPER_ERROR, "m"))
        assertIs<KuotaKitException.Unexpected>(failureOf(BillingResponseCode.ERROR, "m"))
        assertIs<KuotaKitException.Unexpected>(failureOf(12345, "m"))
    }
}
