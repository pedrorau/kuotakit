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

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import com.google.api.services.androidpublisher.model.ProductPurchasesAcknowledgeRequest
import com.google.api.services.androidpublisher.model.SubscriptionPurchasesAcknowledgeRequest
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import dev.pedrorau.kuotakit.backend.domain.PlayOneTimePurchase
import dev.pedrorau.kuotakit.backend.domain.PlayStoreApi
import dev.pedrorau.kuotakit.backend.domain.PlaySubscription
import dev.pedrorau.kuotakit.backend.domain.PurchaseInvalidException
import dev.pedrorau.kuotakit.backend.domain.StoreUnavailableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * The Google Play Developer API. Reads a subscription with `purchases.subscriptionsv2.get` and acknowledges it with
 * `purchases.subscriptions.acknowledge` (v2 has no acknowledge). One-time purchases use `purchases.productsv2` and
 * `purchases.products.acknowledge`. The only class that talks to Google over the network.
 */
class GooglePlayStoreApi internal constructor(
    private val publisher: AndroidPublisher,
    private val packageName: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlayStoreApi {
    override suspend fun subscription(purchaseToken: String): PlaySubscription =
        call(unknownTokenIsInvalid = true) {
            GooglePlaySubscriptions.toDomain(
                publisher
                    .purchases()
                    .subscriptionsv2()
                    .get(packageName, purchaseToken)
                    .execute(),
            )
        }

    override suspend fun acknowledge(
        productId: String,
        purchaseToken: String,
    ) {
        call(unknownTokenIsInvalid = false) {
            publisher
                .purchases()
                .subscriptions()
                .acknowledge(packageName, productId, purchaseToken, SubscriptionPurchasesAcknowledgeRequest())
                .execute()
        }
    }

    override suspend fun oneTimePurchase(purchaseToken: String): PlayOneTimePurchase =
        call(unknownTokenIsInvalid = true) {
            GooglePlayOneTime.toDomain(
                publisher
                    .purchases()
                    .productsv2()
                    .getproductpurchasev2(packageName, purchaseToken)
                    .execute(),
            )
        }

    override suspend fun acknowledgeOneTime(
        productId: String,
        purchaseToken: String,
    ) {
        call(unknownTokenIsInvalid = false) {
            publisher
                .purchases()
                .products()
                .acknowledge(packageName, productId, purchaseToken, ProductPurchasesAcknowledgeRequest())
                .execute()
        }
    }

    /**
     * Reading: Google answering 400, 404 or 410 means "not a purchase of this app" and is the buyer's problem.
     * Acknowledging happens after the purchase was read successfully, so any failure there is ours or a race (two
     * requests acknowledging at once): it is `store_unavailable`, the app retries, and the retry sees it acknowledged.
     * Anything else (credentials, permissions, quota, outage, network) never grants access.
     */
    @Suppress("SwallowedException") // on purpose: the cause of a Google error holds the token (see below)
    private suspend fun <T> call(
        unknownTokenIsInvalid: Boolean,
        block: () -> T,
    ): T =
        try {
            withContext(ioDispatcher) { block() }
        } catch (e: GoogleJsonResponseException) {
            // The exception from Google carries the request URL, which contains the purchase token: keep it out of
            // causes, so a stack trace in a log can never leak it.
            val failure =
                GoogleApiFailure(
                    e.statusCode,
                    e.details
                        ?.errors
                        ?.firstOrNull()
                        ?.reason,
                )
            if (unknownTokenIsInvalid && e.statusCode in INVALID_TOKEN_STATUSES) {
                throw PurchaseInvalidException("Google Play does not recognise the purchase.", failure)
            }
            throw StoreUnavailableException("Google Play answered HTTP ${e.statusCode}.", failure)
        } catch (e: IOException) {
            throw StoreUnavailableException("Google Play could not be reached (${e.javaClass.simpleName}).", e)
        }

    companion object {
        private val INVALID_TOKEN_STATUSES = setOf(400, 404, 410)
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        /** Builds the client from a service account key (the JSON file Google Cloud gives for it). */
        fun fromServiceAccount(
            serviceAccountJson: InputStream,
            packageName: String,
        ): GooglePlayStoreApi {
            val credentials =
                serviceAccountJson.use {
                    GoogleCredentials.fromStream(it).createScoped(AndroidPublisherScopes.ANDROIDPUBLISHER)
                }
            return create(NetHttpTransport(), HttpCredentialsAdapter(credentials), packageName)
        }

        internal fun create(
            transport: HttpTransport,
            auth: HttpRequestInitializer,
            packageName: String,
        ): GooglePlayStoreApi {
            val withTimeouts =
                HttpRequestInitializer { request: HttpRequest ->
                    auth.initialize(request)
                    request.connectTimeout = CONNECT_TIMEOUT_MS
                    request.readTimeout = READ_TIMEOUT_MS
                }
            val publisher =
                AndroidPublisher
                    .Builder(transport, GsonFactory.getDefaultInstance(), withTimeouts)
                    .setApplicationName("KuotaKit")
                    .build()
            return GooglePlayStoreApi(publisher, packageName)
        }
    }
}

/** What went wrong at Google, without the request URL (which holds the purchase token). */
internal class GoogleApiFailure(
    val statusCode: Int,
    val reason: String?,
) : RuntimeException("Google API HTTP $statusCode${reason?.let { ": $it" }.orEmpty()}")
