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

package dev.pedrorau.kuotakit.backend.domain

import dev.pedrorau.kuotakit.contracts.AppStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Validates a purchase with the store and records it (`POST /v1/subscribers/{id}/receipts`, ADR 0003). The order is
 * deliberate: nothing is saved unless the store confirmed the purchase and, when it must be acknowledged, the
 * acknowledgement worked, so a store failure leaves no trace and the app can simply retry.
 *
 * [isOneTime] says which Google products are bought once (`one_time_products` in entitlements.yaml); every other
 * product is read as a subscription. The server decides, not the app (docs/adr/0015).
 */
class ReceiptService(
    private val playStore: PlayStoreApi?,
    private val androidPackageName: String?,
    private val purchases: PurchaseRepository,
    private val clock: Clock,
    private val isOneTime: (productId: String) -> Boolean = { false },
) {
    private val linked = LinkedPurchases(purchases)

    suspend fun submit(
        appUserId: String,
        receipt: ReceiptRequest,
    ) {
        when (receipt) {
            is PlayStoreReceiptRequest -> submitPlayStore(appUserId, receipt)
            is AppStoreReceiptRequest -> throw InvalidReceiptException("App Store receipts are not supported yet")
        }
    }

    /** What Google said about a token, in the terms [accept] needs, whatever kind of product it is. */
    private class Read(
        val attachedUser: String?,
        val acknowledged: Boolean,
        val linkedToken: String?,
        val build: (existing: Purchase?, now: Instant) -> Purchase,
        val acknowledge: suspend () -> Unit,
    )

    private suspend fun submitPlayStore(
        appUserId: String,
        receipt: PlayStoreReceiptRequest,
    ) {
        val api = playStore ?: throw StoreUnavailableException("Google Play is not configured on this backend")
        checkFields(receipt)
        val token = receipt.purchaseToken
        val productId = receipt.productId
        val read =
            if (isOneTime(productId)) {
                val purchase = api.oneTimePurchase(token)
                Read(
                    attachedUser = purchase.obfuscatedAccountId,
                    acknowledged = purchase.acknowledged,
                    linkedToken = null,
                    build = {
                        existing,
                        now,
                        ->
                        PlayOneTimeMapper.toPurchase(purchase, productId, token, existing, now)
                    },
                    acknowledge = { api.acknowledgeOneTime(productId, token) },
                )
            } else {
                val subscription = api.subscription(token)
                Read(
                    attachedUser = subscription.obfuscatedAccountId,
                    acknowledged = subscription.acknowledged,
                    linkedToken = subscription.linkedPurchaseToken,
                    build = { existing, now ->
                        PlaySubscriptionMapper.toPurchase(subscription, productId, token, existing, now)
                    },
                    acknowledge = { api.acknowledge(productId, token) },
                )
            }
        accept(appUserId, token, read)
    }

    private suspend fun accept(
        appUserId: String,
        token: String,
        read: Read,
    ) {
        val owner = purchases.ownerOf(Store.PLAY_STORE, token)
        checkOwnership(appUserId, token, read.attachedUser, owner)

        val now = clock.now()
        val existing = if (owner == null) null else purchases.purchasesOf(appUserId).firstOrNull { it.token == token }
        // Refunded or revoked for good: resubmitting the receipt changes nothing and must not acknowledge it again.
        if (existing?.status?.isFinal() == true) return
        val purchase = read.build(existing, now)

        // Google refunds a purchase that is not acknowledged within three days, so a purchase that grants access is.
        if (!read.acknowledged && purchase.grantsAccessAt(now)) read.acknowledge()
        purchases.save(appUserId, purchase)
        linked.expire(read.linkedToken, token, now)
    }

    /** Cheap checks first: nothing malformed or meant for another app costs a call to Google. */
    private fun checkFields(receipt: PlayStoreReceiptRequest) {
        val problem =
            when {
                receipt.packageName != androidPackageName -> {
                    "The package_name is not the app this backend serves"
                }

                receipt.productId.isBlank() || receipt.productId.length > MAX_FIELD_LENGTH -> {
                    "The product_id is empty or too long"
                }

                receipt.purchaseToken.isBlank() || receipt.purchaseToken.length > MAX_FIELD_LENGTH -> {
                    "The purchase_token is empty or too long"
                }

                else -> {
                    null
                }
            }
        if (problem != null) throw InvalidReceiptException(problem)
    }

    /**
     * The app can attach the user to the purchase when it buys; if it did, only that user may claim it. Whoever
     * stored the token first keeps it.
     */
    private fun checkOwnership(
        appUserId: String,
        token: String,
        attachedUser: String?,
        owner: String?,
    ) {
        val foreign = (attachedUser != null && attachedUser != appUserId) || (owner != null && owner != appUserId)
        if (foreign) throw TokenAlreadyLinkedException(Store.PLAY_STORE, token)
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 4096
    }
}
