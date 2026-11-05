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

import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimeState
import dev.pedrorau.kuotakit.backend.domain.PurchaseInvalidException
import dev.pedrorau.kuotakit.backend.domain.StoreUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.time.Instant

private const val BASE =
    "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.acme.app/purchases"

private val PURCHASED =
    """
    {
      "kind": "androidpublisher#productPurchaseV2",
      "orderId": "GPA.1234",
      "purchaseCompletionTime": "2026-11-01T10:00:00.5Z",
      "acknowledgementState": "ACKNOWLEDGEMENT_STATE_PENDING",
      "obfuscatedExternalAccountId": "anon_buyer",
      "purchaseStateContext": { "purchaseState": "PURCHASED" },
      "productLineItem": [{
        "productId": "lifetime_unlock",
        "productOfferDetails": {
          "quantity": 1,
          "refundableQuantity": 1,
          "consumptionState": "CONSUMPTION_STATE_YET_TO_BE_CONSUMED"
        }
      }]
    }
    """.trimIndent()

private class OneTimeGoogle(
    private val status: Int = 200,
    private val body: String = PURCHASED,
    private val failure: IOException? = null,
) {
    val requests = mutableListOf<String>()

    val api =
        GooglePlayStoreApi.create(
            object : MockHttpTransport() {
                override fun buildRequest(
                    method: String,
                    url: String,
                ): MockLowLevelHttpRequest =
                    object : MockLowLevelHttpRequest(url) {
                        override fun execute(): LowLevelHttpResponse {
                            requests += "$method $url"
                            failure?.let { throw it }
                            return MockLowLevelHttpResponse()
                                .setStatusCode(status)
                                .setContentType("application/json")
                                .setContent(body)
                        }
                    }
            },
            HttpRequestInitializer { },
            "com.acme.app",
        )
}

private fun error(code: Int) = """{"error":{"code":$code,"message":"nope","errors":[{"reason":"x"}]}}"""

class GooglePlayOneTimeApiTest :
    StringSpec({
        "a one-time purchase is read with productsv2 and turned into the domain's types" {
            val google = OneTimeGoogle()
            val purchase = google.api.oneTimePurchase("tok1")
            google.requests shouldBe listOf("GET $BASE/productsv2/tokens/tok1")
            purchase.state shouldBe PlayOneTimeState.PURCHASED
            purchase.completionTime shouldBe Instant.parse("2026-11-01T10:00:00.5Z")
            purchase.acknowledged shouldBe false
            purchase.obfuscatedAccountId shouldBe "anon_buyer"
            purchase.items.single().productId shouldBe "lifetime_unlock"
            purchase.items.single().refundableQuantity shouldBe 1
        }

        "a one-time purchase is acknowledged with products.acknowledge" {
            val google = OneTimeGoogle(body = "{}")
            google.api.acknowledgeOneTime("lifetime_unlock", "tok1")
            google.requests shouldBe listOf("POST $BASE/products/lifetime_unlock/tokens/tok1:acknowledge")
        }

        "every state Google may send is understood, and one it does not know never grants" {
            fun state(raw: String) =
                OneTimeGoogle(body = """{"purchaseStateContext":{"purchaseState":"$raw"},"productLineItem":[]}""").api
            state("PURCHASED").oneTimePurchase("t").state shouldBe PlayOneTimeState.PURCHASED
            state("CANCELLED").oneTimePurchase("t").state shouldBe PlayOneTimeState.CANCELLED
            state("PENDING").oneTimePurchase("t").state shouldBe PlayOneTimeState.PENDING
            state("PURCHASE_STATE_UNSPECIFIED").oneTimePurchase("t").state shouldBe PlayOneTimeState.UNKNOWN
            state("FROM_THE_FUTURE").oneTimePurchase("t").state shouldBe PlayOneTimeState.UNKNOWN
            OneTimeGoogle(body = "{}").api.oneTimePurchase("t").state shouldBe PlayOneTimeState.UNKNOWN
        }

        "missing or unreadable fields become null or the safe default, never a guess" {
            val bare =
                OneTimeGoogle(
                    body = """{"purchaseCompletionTime":"nope","productLineItem":[{}]}""",
                ).api.oneTimePurchase("t")
            bare.completionTime shouldBe null
            bare.acknowledged shouldBe false
            bare.obfuscatedAccountId shouldBe null
            bare.items.single().productId shouldBe ""
            bare.items.single().refundableQuantity shouldBe null
        }

        "a refunded quantity of zero is kept as zero, not as missing" {
            val body = PURCHASED.replace("\"refundableQuantity\": 1", "\"refundableQuantity\": 0")
            OneTimeGoogle(body = body)
                .api
                .oneTimePurchase("t")
                .items
                .single()
                .refundableQuantity shouldBe 0
        }

        "reading: Google saying the token is unknown is the buyer's problem; acknowledging: never" {
            listOf(400, 404, 410).forEach { code ->
                shouldThrow<PurchaseInvalidException> {
                    OneTimeGoogle(
                        status = code,
                        body = error(code),
                    ).api.oneTimePurchase("t")
                }
                val google = OneTimeGoogle(status = code, body = error(code))
                shouldThrow<StoreUnavailableException> { google.api.acknowledgeOneTime("p", "t") }
            }
        }

        "any other failure never grants access" {
            listOf(401, 403, 429, 500, 503).forEach { code ->
                val google = OneTimeGoogle(status = code, body = error(code))
                shouldThrow<StoreUnavailableException> { google.api.oneTimePurchase("t") }
                shouldThrow<StoreUnavailableException> { google.api.acknowledgeOneTime("p", "t") }
            }
            val down = OneTimeGoogle(failure = IOException("connection reset"))
            shouldThrow<StoreUnavailableException> { down.api.oneTimePurchase("t") }
        }

        "an error never carries the purchase token" {
            val e =
                shouldThrow<PurchaseInvalidException> {
                    OneTimeGoogle(
                        status = 404,
                        body = error(404),
                    ).api.oneTimePurchase("SECRET")
                }
            e.message!!.contains("SECRET") shouldBe false
            e.cause!!.message!!.contains("SECRET") shouldBe false
        }
    })
