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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
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
    private val latest = LatestCustomerInfo()
    private val oneAtATime = Mutex()
    private val sync = PurchaseSync(api, billing, appUserId, playObfuscatedAccountId(appUserId), latest)

    /** The last customer info the backend gave, or `null` before the first one. Never computed by the SDK. */
    public val customerInfo: StateFlow<CustomerInfo?> = latest.flow

    public suspend fun getOfferings(): OfferingsResponse = api.offerings()

    public suspend fun getCustomerInfo(): CustomerInfo {
        val stamp = latest.stamp()
        return api.customerInfo(appUserId).also { latest.remember(stamp, it) }
    }

    /**
     * Finds the purchases of this user in the store (the ones that were never sent, a pending payment that was
     * approved, a purchase made on this device before a reinstall of the SDK data) and has the backend check them,
     * then returns what the backend says. Call it when the app comes to the foreground, as Google recommends. A
     * purchase made outside the app, or under another app user id, carries another account id and cannot be recovered.
     */
    public suspend fun restore(): RestoreOutcome = sync.run().outcome

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

                BillingResult.AlreadyOwned -> {
                    alreadyOwned(productId)
                }

                is BillingResult.Purchased -> {
                    val stamp = latest.stamp()
                    val info = api.submitReceipt(appUserId, result.receipt)
                    latest.remember(stamp, info)
                    PurchaseOutcome.Success(info)
                }
            }
        }

    private suspend fun alreadyOwned(productId: String): PurchaseOutcome {
        val result = sync.run()
        val outcome = result.outcome
        return when {
            productId in result.acceptedProducts -> PurchaseOutcome.AlreadyOwned(outcome.customerInfo)

            outcome.refused > 0 -> throw KuotaKitException.PurchaseInvalid(
                "The store says the user already owns this product, but the backend does not accept that purchase",
            )

            else -> throw KuotaKitException.InvalidRequest(
                "The store says the user already owns this product, but no purchase of it was found for this app " +
                    "user: it was made under another app user id, or outside the app",
            )
        }
    }

    private val jobs = mutableListOf<Job>()

    /**
     * Sends the purchases that arrive while no purchase is waiting, and the ones already owned, to the backend. A
     * failure changes nothing visible: the next trigger (an update, `restore()`) finds the purchase again.
     */
    internal fun startBackgroundSync(scope: CoroutineScope) {
        // Listening starts at once, so an update that arrives while the first sync runs is not missed.
        jobs += scope.launch { billing.purchaseUpdates.catch { }.collect { quietly { restore() } } }
        jobs += scope.launch { quietly { restore() } }
    }

    /** Stops the background sync (when the SDK is configured again). */
    internal fun stopBackgroundSync() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    // Whatever goes wrong in the background (the store, the network, a bug) must not end the app: the next trigger
    // tries again, and nothing is shown as success.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            // Kept for the next trigger.
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

        /**
         * Sets the SDK up; call it once, at start-up. Calling it again replaces the previous configuration. It is the
         * one place that makes the scope of the background sync; tests pass their own to `startBackgroundSync`.
         */
        @Suppress("InjectDispatcher")
        public fun configure(config: KuotaKitConfig): KuotaKit =
            create(config).also {
                configured?.stopBackgroundSync()
                configured = it
                it.startBackgroundSync(CoroutineScope(SupervisorJob() + Dispatchers.Default))
            }

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
