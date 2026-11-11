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

package dev.pedrorau.kuotakit.backend.google

import com.google.api.services.androidpublisher.model.DeferredItemReplacement
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseLineItem
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun item(
    product: String,
    replacedBy: String? = null,
) = SubscriptionPurchaseLineItem()
    .setProductId(product)
    .setExpiryTime("2026-12-01T00:00:00Z")
    .apply { replacedBy?.let { deferredItemReplacement = DeferredItemReplacement().setProductId(it) } }

/** Google's `deferredItemReplacement` is read into the domain, and only when it says something (docs/adr/0020). */
class GooglePlayDeferredTest :
    StringSpec({
        "the product that will replace an item is read from deferredItemReplacement" {
            val purchase =
                SubscriptionPurchaseV2().setLineItems(
                    listOf(item("annual", replacedBy = "monthly"), item("monthly")),
                )
            val items = GooglePlaySubscriptions.toDomain(purchase).items
            items.map { it.productId to it.deferredReplacement } shouldBe
                listOf("annual" to "monthly", "monthly" to null)
        }

        "an empty replacement product is no replacement" {
            val purchase = SubscriptionPurchaseV2().setLineItems(listOf(item("annual", replacedBy = " ")))
            GooglePlaySubscriptions
                .toDomain(purchase)
                .items
                .single()
                .deferredReplacement shouldBe null
        }

        "an item with no deferred change has none" {
            GooglePlaySubscriptions
                .toDomain(
                    SubscriptionPurchaseV2().setLineItems(listOf(item("annual"))),
                ).items
                .single()
                .deferredReplacement shouldBe
                null
        }

        "an item with no order next to another item is not owned yet; a lone item is, whatever it carries" {
            fun owned(vararg orders: String?) =
                GooglePlaySubscriptions
                    .toDomain(
                        SubscriptionPurchaseV2().setLineItems(
                            orders.mapIndexed {
                                i,
                                order,
                                ->
                                item("p$i").setLatestSuccessfulOrderId(order)
                            },
                        ),
                    ).items
                    .map { it.ownedByUser }
            owned(null) shouldBe listOf(true)
            owned("GPA.1", null) shouldBe listOf(true, false)
            owned("GPA.1", "GPA.2") shouldBe listOf(true, true)
            owned("", "GPA.2") shouldBe listOf(false, true)
        }
    })
