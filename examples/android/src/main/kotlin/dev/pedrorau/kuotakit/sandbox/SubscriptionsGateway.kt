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

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.sdk.CachedCustomerInfo
import dev.pedrorau.kuotakit.sdk.KuotaKit
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.PlanChange
import dev.pedrorau.kuotakit.sdk.PurchaseOutcome
import dev.pedrorau.kuotakit.sdk.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The calls of the SDK that the screens use. `KuotaKit` is a final class that only the SDK can build, so the view
 * models depend on this interface (and the tests give them their own), and one class implements it with the SDK. Every
 * call throws [KuotaKitException] on failure, and nothing is granted when it does.
 */
internal interface SubscriptionsGateway {
    /** The anonymous id of this user. A secret: show it truncated and never log it. */
    val appUserId: String

    /** The last answer of the backend, or `null` before the first one. */
    val customerInfo: StateFlow<CustomerInfo?>

    /** The last answer of the backend kept on the device, with when it came. Not proof of access. */
    val lastKnown: StateFlow<CachedCustomerInfo?>

    suspend fun offerings(): OfferingsResponse

    suspend fun refresh(): CustomerInfo

    suspend fun purchase(
        pkg: OfferingPackage,
        change: PlanChange? = null,
    ): PurchaseOutcome

    suspend fun restore(): RestoreOutcome
}

/** The gateway over the real SDK. */
internal class KuotaKitGateway(
    private val kit: KuotaKit,
) : SubscriptionsGateway {
    override val appUserId: String get() = kit.appUserId
    override val customerInfo: StateFlow<CustomerInfo?> get() = kit.customerInfo
    override val lastKnown: StateFlow<CachedCustomerInfo?> get() = kit.lastKnownCustomerInfo

    override suspend fun offerings(): OfferingsResponse = kit.getOfferings()

    override suspend fun refresh(): CustomerInfo = kit.getCustomerInfo()

    override suspend fun purchase(
        pkg: OfferingPackage,
        change: PlanChange?,
    ): PurchaseOutcome = kit.purchase(pkg, change)

    override suspend fun restore(): RestoreOutcome = kit.restore()
}

/** Used while the app has no usable address or key: every call says so, and nothing is shown as owned. */
internal object UnconfiguredGateway : SubscriptionsGateway {
    override val appUserId: String = ""
    override val customerInfo: StateFlow<CustomerInfo?> = MutableStateFlow(null)
    override val lastKnown: StateFlow<CachedCustomerInfo?> = MutableStateFlow(null)

    override suspend fun offerings(): OfferingsResponse = throw KuotaKitException.NotConfigured()

    override suspend fun refresh(): CustomerInfo = throw KuotaKitException.NotConfigured()

    override suspend fun purchase(
        pkg: OfferingPackage,
        change: PlanChange?,
    ): PurchaseOutcome = throw KuotaKitException.NotConfigured()

    override suspend fun restore(): RestoreOutcome = throw KuotaKitException.NotConfigured()
}
