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

package dev.pedrorau.kuotakit.backend.storage

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

/**
 * The tables of `db/migration` (V1 subscribers and purchases, V3 store events, V4 pending endings). Flyway creates
 * them; Exposed only reads and writes.
 */
internal object Subscribers : Table("subscribers") {
    val appUserId = text("app_user_id")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(appUserId)
}

internal object Purchases : Table("purchases") {
    val id = long("id").autoIncrement()
    val appUserId = text("app_user_id")
    val store = text("store")
    val productId = text("product_id")
    val purchaseToken = text("purchase_token")
    val kind = text("kind")
    val status = text("status")
    val purchasedAt = timestamp("purchased_at")
    val expiresAt = timestamp("expires_at").nullable()
    val autoRenewing = bool("auto_renewing")
    val periodType = text("period_type")
    val billingIssueDetectedAt = timestamp("billing_issue_detected_at").nullable()
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object StoreEvents : Table("store_events") {
    val store = text("store")
    val messageId = text("message_id")
    val receivedAt = timestamp("received_at")

    override val primaryKey = PrimaryKey(store, messageId)
}

internal object PendingEndings : Table("pending_endings") {
    val store = text("store")
    val purchaseToken = text("purchase_token")
    val status = text("status")
    val receivedAt = timestamp("received_at")

    override val primaryKey = PrimaryKey(store, purchaseToken)
}
