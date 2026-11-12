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
import dev.pedrorau.kuotakit.contracts.Entitlement
import dev.pedrorau.kuotakit.contracts.Offering
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.PeriodType
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.StoreProducts
import dev.pedrorau.kuotakit.sdk.CachedCustomerInfo
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.PlanChange
import dev.pedrorau.kuotakit.sdk.PurchaseOutcome
import dev.pedrorau.kuotakit.sdk.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

internal val MONTHLY = OfferingPackage("monthly", StoreProducts("premium_monthly", null))
internal val ANNUAL = OfferingPackage("annual", StoreProducts("premium_annual", null))
internal val OFFERINGS = OfferingsResponse("default", listOf(Offering("default", listOf(MONTHLY, ANNUAL))))
internal val NOW = Instant.parse("2026-11-11T12:00:00Z")

internal fun info(
    product: String?,
    active: Boolean = true,
) = CustomerInfo(
    appUserId = "anon_a_secret_user_id",
    requestDate = NOW,
    entitlements =
        if (product == null) {
            emptyMap()
        } else {
            mapOf("premium" to Entitlement(active, product, Store.PLAY_STORE, PeriodType.NORMAL, NOW, true, null))
        },
    activeSubscriptions = listOfNotNull(product.takeIf { active }),
    nonSubscriptionPurchases = emptyList(),
)

/** A gateway that answers what the test says and records what it was asked. */
internal class FakeGateway : SubscriptionsGateway {
    override val appUserId = "anon_a_secret_user_id"
    override val customerInfo = MutableStateFlow<CustomerInfo?>(null)
    override val lastKnown: MutableStateFlow<CachedCustomerInfo?> = MutableStateFlow(null)

    var offeringsAnswer: () -> OfferingsResponse = { OFFERINGS }
    var refreshAnswer: () -> CustomerInfo = { info(null) }
    var purchaseAnswer: suspend () -> PurchaseOutcome = { PurchaseOutcome.Cancelled }
    var restoreAnswer: suspend () -> RestoreOutcome = { RestoreOutcome(info(null), 0, 0, 0) }

    val purchases = mutableListOf<Pair<OfferingPackage, PlanChange?>>()

    var restoreCalls = 0

    override suspend fun offerings() = offeringsAnswer()

    // Like the SDK, every answer of the backend becomes the newest `customerInfo`.
    override suspend fun refresh() = refreshAnswer().also { customerInfo.value = it }

    override suspend fun purchase(
        pkg: OfferingPackage,
        change: PlanChange?,
    ): PurchaseOutcome {
        purchases += pkg to change
        val outcome = purchaseAnswer()
        when (outcome) {
            is PurchaseOutcome.Success -> customerInfo.value = outcome.customerInfo
            is PurchaseOutcome.AlreadyOwned -> customerInfo.value = outcome.customerInfo
            else -> Unit
        }
        return outcome
    }

    override suspend fun restore(): RestoreOutcome {
        restoreCalls++
        return restoreAnswer().also { customerInfo.value = it.customerInfo }
    }
}

internal fun failing(): Nothing = throw KuotaKitException.Network("the connection to http://secret-host:8080 failed")
