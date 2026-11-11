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
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.ReceiptRequest
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer

private const val CONNECT_TIMEOUT_MS = 10_000L
private const val REQUEST_TIMEOUT_MS = 30_000L

/** The HTTP engine of the platform (OkHttp on the JVM and Android, Darwin on iOS). */
internal expect fun platformEngine(): HttpClientEngine

/**
 * The three calls the SDK makes to the backend (`docs/api-spec.md`). Nothing is retried: a failure is an exception
 * the app sees, and nothing is granted because of it.
 */
internal class ApiClient(
    private val baseUrl: String,
    private val apiKey: String,
    engine: HttpClientEngine = platformEngine(),
    requestTimeoutMillis: Long = REQUEST_TIMEOUT_MS,
) {
    private val http =
        HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                this.requestTimeoutMillis = requestTimeoutMillis
            }
        }

    suspend fun offerings(): OfferingsResponse =
        read(OfferingsResponse.serializer()) {
            http.get {
                url(baseUrl) { appendPathSegments("v1", "offerings") }
                bearerAuth(apiKey)
            }
        }

    suspend fun customerInfo(appUserId: String): CustomerInfo =
        read(CustomerInfo.serializer()) {
            http.get {
                url(baseUrl) { appendPathSegments("v1", "subscribers", appUserId) }
                bearerAuth(apiKey)
            }
        }

    suspend fun submitReceipt(
        appUserId: String,
        receipt: ReceiptRequest,
    ): CustomerInfo =
        read(CustomerInfo.serializer()) {
            http.post {
                url(baseUrl) { appendPathSegments("v1", "subscribers", appUserId, "receipts") }
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(kuotaKitJson.encodeToString(ReceiptRequest.serializer(), receipt))
            }
        }

    private class Answer(
        val status: Int,
        val retryAfter: String?,
        val body: String,
    )

    /** A transport failure is [KuotaKitException.Network]; an error answer is mapped; a good answer is decoded. */
    private suspend fun <T> read(
        serializer: KSerializer<T>,
        call: suspend () -> HttpResponse,
    ): T {
        val answer = send(call)
        if (answer.status !in HTTP_OK_RANGE) throw mapFailure(answer.status, answer.retryAfter, answer.body)
        return decode(serializer, answer.body)
    }

    // The exceptions of an HTTP engine are different on every platform (OkHttp, Darwin), so any that is not a
    // cancellation means the backend could not be reached. The cause is not kept: the message of an engine exception
    // holds the address asked, which contains the app user id, a secret (ADR 0009) that must not reach a log.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun send(call: suspend () -> HttpResponse): Answer =
        try {
            val response = call()
            Answer(response.status.value, response.headers[HttpHeaders.RetryAfter], response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw KuotaKitException.Network("The backend could not be reached (${e::class.simpleName})")
        }

    // A model that refuses what the backend sent (a failed `require`) is an answer the SDK does not understand too.
    private fun <T> decode(
        serializer: KSerializer<T>,
        body: String,
    ): T =
        try {
            kuotaKitJson.decodeFromString(serializer, body)
        } catch (e: IllegalArgumentException) {
            throw KuotaKitException.Unexpected("The backend answered with something the SDK does not understand", e)
        }
}

private val HTTP_OK_RANGE = 200..299
