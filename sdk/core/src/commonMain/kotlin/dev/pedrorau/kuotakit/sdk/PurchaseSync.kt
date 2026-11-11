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

import dev.pedrorau.kuotakit.contracts.AppStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * The last customer info the backend gave. Answers can come back in another order than the requests went out, so
 * only the newest request may set it.
 */
internal class LatestCustomerInfo(
    private val store: KeyValueStore,
    private val appUserId: String,
    private val clock: Clock,
) {
    private val value = MutableStateFlow<CustomerInfo?>(null)
    private val cache = MutableStateFlow(loadCached(store, appUserId))

    private val lock = Mutex()
    private var issued = 0L
    private var applied = 0L

    val flow: StateFlow<CustomerInfo?> = value.asStateFlow()
    val cached: StateFlow<CachedCustomerInfo?> = cache.asStateFlow()

    suspend fun stamp(): Long = lock.withLock { ++issued }

    suspend fun remember(
        stamp: Long,
        info: CustomerInfo,
    ) = lock.withLock {
        if (stamp > applied) {
            applied = stamp
            value.value = info
            // Only what would be read back is kept: an answer about another app user is never the cache of this one.
            if (info.appUserId == appUserId) {
                CachedCustomerInfo(info, clock.now()).also {
                    cache.value = it
                    saveCachedQuietly(store, it)
                }
            }
        }
    }
}

/** What a sync does with a purchase the store says the user owns: decided here and nowhere else. */
internal sealed interface OwnedAction {
    /** A completed purchase of this app user: the backend must see it. */
    data class Send(
        val receipt: ReceiptRequest,
    ) : OwnedAction

    /** Not paid yet: it is sent when the store says it is completed. */
    data object Waiting : OwnedAction

    /** No account id, or the account id of another app user: the backend would refuse it (ADR 0017). */
    data object NotOurs : OwnedAction

    /** Any other state: never sent. */
    data object Ignore : OwnedAction
}

internal fun classify(
    purchase: OwnedPurchase,
    accountId: String,
): OwnedAction =
    when {
        purchase.state == OwnedState.UNKNOWN -> OwnedAction.Ignore
        purchase.accountId != accountId -> OwnedAction.NotOurs
        purchase.state == OwnedState.PENDING -> OwnedAction.Waiting
        else -> OwnedAction.Send(purchase.receipt)
    }

/** What one sync did, with the products it got accepted (the outcome the app sees does not carry them). */
internal class SyncResult(
    val outcome: RestoreOutcome,
    val acceptedProducts: Set<String>,
)

/**
 * Finds the purchases of the user in the store and has the backend check them. The store keeps a purchase until it is
 * confirmed or refunded, so it is the queue: nothing is stored on the device, and what could not be sent now is found
 * again by the next sync. One sync at a time. A purchase the backend already answered (accepted or refused) is not sent
 * again in the same run of the app: the backend limits the receipts of one app user (ADR 0016), and the answer would
 * not change; the customer info is always read again, which is where renewals and refunds show.
 */
internal class PurchaseSync(
    private val api: ApiClient,
    private val billing: BillingAdapter,
    private val appUserId: String,
    private val accountId: String,
    private val latest: LatestCustomerInfo,
) {
    private val lock = Mutex()
    private val answered = mutableSetOf<String>()
    private val accepted = mutableSetOf<String>()

    suspend fun run(): SyncResult =
        lock.withLock {
            var submitted = 0
            var refused = 0
            var unattributed = 0
            for (purchase in billing.ownedPurchases()) {
                when (val action = classify(purchase, accountId)) {
                    is OwnedAction.Send -> {
                        val key = keyOf(action.receipt)
                        if (key !in answered) {
                            if (send(action.receipt)) submitted++ else refused++
                            answered += key
                        }
                    }

                    OwnedAction.NotOurs -> {
                        unattributed++
                    }

                    OwnedAction.Waiting, OwnedAction.Ignore -> {
                        Unit
                    }
                }
            }
            val stamp = latest.stamp()
            val info = api.customerInfo(appUserId).also { latest.remember(stamp, it) }
            SyncResult(RestoreOutcome(info, submitted, refused, unattributed), accepted.toSet())
        }

    private fun keyOf(receipt: ReceiptRequest): String =
        when (receipt) {
            is PlayStoreReceiptRequest -> "${receipt.productId}|${receipt.purchaseToken}"
            is AppStoreReceiptRequest -> receipt.signedTransaction
        }

    /** `true` if the backend accepted it, `false` if it refused this purchase; any other failure stops the sync. */
    private suspend fun send(receipt: ReceiptRequest): Boolean {
        val stamp = latest.stamp()
        return try {
            latest.remember(stamp, api.submitReceipt(appUserId, receipt))
            if (receipt is PlayStoreReceiptRequest) accepted += receipt.productId
            true
        } catch (
            @Suppress("SwallowedException") refusal: KuotaKitException.PurchaseInvalid,
        ) {
            false
        } catch (
            @Suppress("SwallowedException") refusal: KuotaKitException.TokenAlreadyLinked,
        ) {
            false
        } catch (
            @Suppress("SwallowedException") refusal: KuotaKitException.InvalidRequest,
        ) {
            false
        }
    }
}
