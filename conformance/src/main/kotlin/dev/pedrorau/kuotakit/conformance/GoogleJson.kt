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

package dev.pedrorau.kuotakit.conformance

import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

private const val ONE_UNIT = 1
private const val BASE = "/androidpublisher/v3/applications/${Fixture.PACKAGE_NAME}/purchases"

/** The SHA-256 of the app user id in lowercase hex: the account id the app attaches to a purchase (ADR 0017). */
internal fun accountIdOf(appUserId: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(appUserId.toByteArray())
    return hash.joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** A purchase token nobody used before (the backend keeps what it learned in earlier runs). */
internal fun freshToken() = "tok_" + Random.nextLong().toString(Character.MAX_RADIX).removePrefix("-")

/** A moment [days] days from now (negative for the past), as Google writes it. */
internal fun inDays(days: Long): String =
    Instant
        .now()
        .plus(days, ChronoUnit.DAYS)
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()

internal fun inMinutes(minutes: Long): String =
    Instant
        .now()
        .plus(minutes, ChronoUnit.MINUTES)
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()

/** The paths of the Play Developer API that the backend calls (ADR 0018), and the answers of Google for them. */
internal object GoogleJson {
    fun subscriptionPath(token: String) = "$BASE/subscriptionsv2/tokens/$token"

    fun oneTimePath(token: String) = "$BASE/productsv2/tokens/$token"

    fun acknowledgePath(
        productId: String,
        token: String,
    ) = "$BASE/subscriptions/$productId/tokens/$token:acknowledge"

    fun oneTimeAcknowledgePath(
        productId: String,
        token: String,
    ) = "$BASE/products/$productId/tokens/$token:acknowledge"

    /** What `subscriptionsv2.get` answers. A null [expiry] or [account] leaves that field out. */
    @Suppress("LongParameterList") // the fields of Google's answer
    fun subscription(
        state: String = "ACTIVE",
        productId: String = "premium_monthly",
        expiry: String? = inDays(ACTIVE_DAYS),
        account: String?,
        acknowledged: Boolean = false,
        autoRenew: Boolean = true,
        phase: String = "basePrice",
        linked: String? = null,
    ): String {
        val ack = if (acknowledged) "ACKNOWLEDGED" else "PENDING"
        val accountPart =
            account?.let { """"externalAccountIdentifiers":{"obfuscatedExternalAccountId":"$it"},""" }.orEmpty()
        val linkedPart = linked?.let { """"linkedPurchaseToken":"$it",""" }.orEmpty()
        val expiryPart = expiry?.let { """"expiryTime":"$it",""" }.orEmpty()
        return """
            {"subscriptionState":"SUBSCRIPTION_STATE_$state","startTime":"${inDays(-1)}",
             "acknowledgementState":"ACKNOWLEDGEMENT_STATE_$ack",$accountPart$linkedPart
             "lineItems":[{"productId":"$productId",$expiryPart
               "autoRenewingPlan":{"autoRenewEnabled":$autoRenew},"offerPhase":{"$phase":{}}}]}
            """.trimIndent()
    }

    /**
     * What `subscriptionsv2.get` answers for a plan change made with `ReplacementMode.DEFERRED` (docs/adr/0020): two
     * line items, the old one with auto-renew off, ending when the change takes effect and naming the product that
     * replaces it, and the new one, which has no end date until then (null [newExpiry]).
     */
    @Suppress("LongParameterList") // the fields of Google's answer
    fun deferred(
        oldProduct: String,
        oldExpiry: String,
        newProduct: String,
        newExpiry: String?,
        account: String?,
        linked: String,
        acknowledged: Boolean = false,
        newOwned: Boolean = false,
    ): String {
        val ack = if (acknowledged) "ACKNOWLEDGED" else "PENDING"
        val newOrder = if (newOwned) """"latestSuccessfulOrderId":"GPA.new",""" else ""
        val accountPart =
            account?.let { """"externalAccountIdentifiers":{"obfuscatedExternalAccountId":"$it"},""" }.orEmpty()
        val newExpiryPart = newExpiry?.let { """"expiryTime":"$it",""" }.orEmpty()
        return """
            {"subscriptionState":"SUBSCRIPTION_STATE_ACTIVE","startTime":"${inDays(-1)}",
             "acknowledgementState":"ACKNOWLEDGEMENT_STATE_$ack",$accountPart"linkedPurchaseToken":"$linked",
             "lineItems":[
              {"productId":"$oldProduct","latestSuccessfulOrderId":"GPA.old","expiryTime":"$oldExpiry","autoRenewingPlan":{"autoRenewEnabled":false},
               "offerPhase":{"basePrice":{}},"deferredItemReplacement":{"productId":"$newProduct"}},
              {"productId":"$newProduct",$newOrder$newExpiryPart"autoRenewingPlan":{"autoRenewEnabled":true},
               "offerPhase":{"basePrice":{}}}]}
            """.trimIndent()
    }

    /** What `productsv2.getproductpurchasev2` answers. A null [refundable] leaves the refundable quantity out. */
    @Suppress("LongParameterList") // the fields of Google's answer
    fun oneTime(
        state: String = "PURCHASED",
        productId: String = "lifetime_unlock",
        refundable: Int? = ONE_UNIT,
        account: String?,
        acknowledged: Boolean = false,
    ): String {
        val ack = if (acknowledged) "ACKNOWLEDGED" else "PENDING"
        val accountPart = account?.let { """"obfuscatedExternalAccountId":"$it",""" }.orEmpty()
        val refundablePart = refundable?.let { """"refundableQuantity":$it""" }.orEmpty()
        return """
            {"purchaseStateContext":{"purchaseState":"$state"},"purchaseCompletionTime":"${inDays(-1)}",
             "acknowledgementState":"ACKNOWLEDGEMENT_STATE_$ack",$accountPart
             "productLineItem":[{"productId":"$productId","productOfferDetails":{$refundablePart}}]}
            """.trimIndent()
    }

    fun error(
        code: Int,
        status: String = "ERROR",
    ) = googleError(code, status, "Scripted by the conformance suite.")
}
