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

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What a backend (or the fake Google) answered. */
internal class Reply(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    override fun toString() = "$status ${body.take(MAX_SHOWN)}"

    private companion object {
        const val MAX_SHOWN = 200
    }
}

/** A plain HTTP client: no redirects, no retries, so what the suite sees is exactly what the server sent. */
internal class Http(
    private val timeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
) {
    private val client =
        HttpClient
            .newBuilder()
            .followRedirects(
                HttpClient.Redirect.NEVER,
            ).connectTimeout(timeout)
            .build()

    fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Reply {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(timeout)
                .method(method, publisher)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.headers().map(), response.body())
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 20L
    }
}
