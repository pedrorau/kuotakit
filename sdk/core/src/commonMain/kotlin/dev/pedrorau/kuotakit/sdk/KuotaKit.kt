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

package dev.pedrorau.kuotakit.sdk

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.playObfuscatedAccountId
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [KuotaKit.configure] needs. The [apiKey] is the public key of the app (`pk_...`), never a secret key. */
public class KuotaKitConfig(
    public val baseUrl: String,
    public val apiKey: String,
    public val billing: BillingAdapter,
    public val storage: KeyValueStore,
)

/**
 * KuotaKit for an app: offerings, purchases and what the user may access. Everything the SDK shows about access is
 * what the backend answered; the SDK never works it out itself (ADR 0003), and a failure never grants anything.
 */
public class KuotaKit internal constructor(
    private val api: ApiClient,
    private val billing: BillingAdapter,
    /** The anonymous id of this user, made on first use and kept on the device. It is a secret: do not log it. */
    public val appUserId: String,
) {
    private val latest = MutableStateFlow<CustomerInfo?>(null)
    private val oneAtATime = Mutex()

    // Answers can come back in another order than the requests went out: only the newest request may set
    // `customerInfo`.
    private val stampLock = Mutex()
    private var issued = 0L
    private var applied = 0L

    private suspend fun stamp(): Long = stampLock.withLock { ++issued }

    private suspend fun remember(
        stamp: Long,
        info: CustomerInfo,
    ) = stampLock.withLock {
        if (stamp > applied) {
            applied = stamp
            latest.value = info
        }
    }

    /** The last customer info the backend gave, or `null` before the first one. Never computed by the SDK. */
    public val customerInfo: StateFlow<CustomerInfo?> = latest.asStateFlow()

    public suspend fun getOfferings(): OfferingsResponse = api.offerings()

    public suspend fun getCustomerInfo(): CustomerInfo {
        val stamp = stamp()
        return api.customerInfo(appUserId).also { remember(stamp, it) }
    }

    /**
     * Buys [pkg] with the store of the platform and has the backend check it. The user may cancel or the payment may
     * be pending: those are outcomes, not failures. Only one purchase runs at a time.
     */
    public suspend fun purchase(pkg: OfferingPackage): PurchaseOutcome =
        oneAtATime.withLock {
            val productId =
                productOf(pkg, billing.store)
                    ?: throw KuotaKitException.InvalidRequest("The package '${pkg.id}' is not sold on this store")
            val request = PurchaseRequest(productId, appUserId, playObfuscatedAccountId(appUserId))
            when (val result = billing.purchase(request)) {
                BillingResult.Cancelled -> {
                    PurchaseOutcome.Cancelled
                }

                BillingResult.Pending -> {
                    PurchaseOutcome.Pending
                }

                is BillingResult.Purchased -> {
                    val stamp = stamp()
                    val info = api.submitReceipt(appUserId, result.receipt)
                    remember(stamp, info)
                    PurchaseOutcome.Success(info)
                }
            }
        }

    private fun productOf(
        pkg: OfferingPackage,
        store: Store,
    ): String? =
        when (store) {
            Store.PLAY_STORE -> pkg.products.playStore
            Store.APP_STORE -> pkg.products.appStore
            Store.UNKNOWN -> null
        }

    public companion object {
        @kotlin.concurrent.Volatile
        private var configured: KuotaKit? = null

        internal fun reset() {
            configured = null
        }

        /** The configured instance. Throws [KuotaKitException.NotConfigured] before [configure]. */
        public val shared: KuotaKit
            get() = configured ?: throw KuotaKitException.NotConfigured()

        /** Sets the SDK up; call it once, at start-up. Calling it again replaces the previous configuration. */
        public fun configure(config: KuotaKitConfig): KuotaKit = create(config).also { configured = it }

        internal fun create(
            config: KuotaKitConfig,
            engine: HttpClientEngine? = null,
        ): KuotaKit {
            validate(config)
            val api =
                if (engine == null) {
                    ApiClient(config.baseUrl, config.apiKey)
                } else {
                    ApiClient(config.baseUrl, config.apiKey, engine)
                }
            return KuotaKit(api, config.billing, appUserIdIn(config.storage))
        }

        private fun validate(config: KuotaKitConfig) {
            val problem =
                when {
                    !config.baseUrl.startsWith("https://") && !config.baseUrl.startsWith("http://") -> {
                        "baseUrl must be an http or https address"
                    }

                    config.apiKey.startsWith("sk_") -> {
                        "This is a secret key (sk_...): it must never be in an app. Use the public key of the app (pk_...)"
                    }

                    !config.apiKey.startsWith("pk_") -> {
                        "The API key of an app starts with pk_"
                    }

                    else -> {
                        null
                    }
                }
            if (problem != null) throw KuotaKitException.InvalidConfiguration(problem)
        }
    }
}
