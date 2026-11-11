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

package dev.pedrorau.kuotakit.sdk.e2e

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import kotlin.random.Random

/** Where the stack of docker-compose.conformance.yml answers, and what its fixtures are (docs/conformance.md). */
internal object World {
    val backend: String = System.getenv("KUOTAKIT_E2E_BACKEND_URL") ?: "http://localhost:8080"
    val control: String = System.getenv("KUOTAKIT_E2E_CONTROL_URL") ?: "http://localhost:9001"
    const val APP_KEY = "pk_conformance_app_key_for_tests_only"
    const val PACKAGE = "com.example.conformance"
    const val AUDIENCE = "http://backend:8080/v1/webhooks/google"
    const val PUSH_EMAIL = "push@conformance.iam.gserviceaccount.com"
    val oneTimeProducts = setOf("lifetime_unlock")
}

internal class Reply(
    val status: Int,
    val body: String,
)

private val http = HttpClient.newHttpClient()

internal fun send(
    method: String,
    url: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
): Reply {
    val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
    val request =
        HttpRequest
            .newBuilder(URI.create(url))
            .method(method, publisher)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    return Reply(response.statusCode(), response.body())
}

/** The control API of the fake Google: what it answers to the backend, and the push tokens of the webhook. */
internal object Google {
    private const val JSON = "application/json"

    fun answer(
        path: String,
        body: String,
        method: String = "GET",
    ) {
        val json = """{"method":"$method","path":"$path","status":200,"body":${quote(body)}}"""
        check(send("POST", World.control + "/control/answer", json, mapOf("Content-Type" to JSON)).status == 204)
    }

    fun calls(): List<String> =
        Regex(""""method":"([A-Z]+)","path":"([^"]+)"""")
            .findAll(send("GET", World.control + "/control/calls").body)
            .map { it.groupValues[1] + " " + it.groupValues[2] }
            .toList()

    fun pushToken(): String =
        send(
            "POST",
            World.control + "/control/push-token",
            """{"audience":"${World.AUDIENCE}","email":"${World.PUSH_EMAIL}"}""",
            mapOf("Content-Type" to JSON),
        ).body

    /** Pub/Sub tells the webhook that a purchase was refunded. */
    fun refund(token: String): Int {
        val notification =
            """{"version":"1.0","packageName":"${World.PACKAGE}","eventTimeMillis":"1767225600000",""" +
                """"voidedPurchaseNotification":{"purchaseToken":"$token","orderId":"GPA.1","productType":1,"refundType":1}}"""
        val data = Base64.getEncoder().encodeToString(notification.toByteArray())
        val push = """{"message":{"data":"$data","messageId":"m-${Random.nextLong()}"}}"""
        return send(
            "POST",
            World.backend + "/v1/webhooks/google",
            push,
            mapOf("Authorization" to "Bearer ${pushToken()}", "Content-Type" to JSON),
        ).status
    }

    private fun quote(text: String) =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}

private const val BASE = "/androidpublisher/v3/applications/${World.PACKAGE}/purchases"

internal fun subscriptionPath(token: String) = "$BASE/subscriptionsv2/tokens/$token"

internal fun oneTimePath(token: String) = "$BASE/productsv2/tokens/$token"

internal fun inDays(days: Long): String =
    Instant
        .now()
        .plus(days, ChronoUnit.DAYS)
        .truncatedTo(ChronoUnit.SECONDS)
        .toString()

internal fun freshToken() = "tok_" + Random.nextLong().toString(Character.MAX_RADIX).removePrefix("-")

/** What Google answers for a subscription: [account] is what the SDK attached when it started the purchase. */
internal fun subscriptionJson(
    productId: String,
    account: String?,
    expiry: String = inDays(30),
    linked: String? = null,
    state: String = "ACTIVE",
): String {
    val accountPart =
        account
            ?.let { """"externalAccountIdentifiers":{"obfuscatedExternalAccountId":"$it"},""" }
            .orEmpty()
    val linkedPart = linked?.let { """"linkedPurchaseToken":"$it",""" }.orEmpty()
    return """
        {"subscriptionState":"SUBSCRIPTION_STATE_$state","startTime":"${inDays(-1)}",
         "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING",$accountPart$linkedPart
         "lineItems":[{"productId":"$productId","expiryTime":"$expiry","autoRenewingPlan":{"autoRenewEnabled":true},
           "offerPhase":{"basePrice":{}}}]}
        """.trimIndent()
}

/** A subscription changed with `ReplacementMode.DEFERRED`: the old item stays, not renewing, until its end. */
internal fun deferredJson(
    oldProduct: String,
    newProduct: String,
    account: String,
    linked: String,
    oldEnds: String = inDays(20),
): String =
    """
    {"subscriptionState":"SUBSCRIPTION_STATE_ACTIVE","startTime":"${inDays(-1)}",
     "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING",
     "externalAccountIdentifiers":{"obfuscatedExternalAccountId":"$account"},"linkedPurchaseToken":"$linked",
     "lineItems":[
      {"productId":"$oldProduct","latestSuccessfulOrderId":"GPA.old","expiryTime":"$oldEnds",
       "autoRenewingPlan":{"autoRenewEnabled":false},"offerPhase":{"basePrice":{}},
       "deferredItemReplacement":{"productId":"$newProduct"}},
      {"productId":"$newProduct","autoRenewingPlan":{"autoRenewEnabled":true},"offerPhase":{"basePrice":{}}}]}
    """.trimIndent()

internal fun oneTimeJson(
    productId: String,
    account: String?,
): String {
    val accountPart = account?.let { """"obfuscatedExternalAccountId":"$it",""" }.orEmpty()
    return """
        {"purchaseStateContext":{"purchaseState":"PURCHASED"},"purchaseCompletionTime":"${inDays(-1)}",
         "acknowledgementState":"ACKNOWLEDGEMENT_STATE_PENDING",$accountPart
         "productLineItem":[{"productId":"$productId","productOfferDetails":{"refundableQuantity":1}}]}
        """.trimIndent()
}
