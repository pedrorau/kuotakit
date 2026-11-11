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

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The address of every call, as the engine receives it, for the forms of base address an app can give. A real engine
 * (OkHttp) refuses what a mock accepts, so the text itself is checked: this is the bug a backend on `localhost:8080`
 * showed in the end-to-end test.
 */
class RequestUrlTest {
    private suspend fun urlsFor(baseUrl: String): List<String> {
        val urls = mutableListOf<String>()
        val engine =
            MockEngine { request ->
                urls += request.url.toString()
                respond(
                    Samples.CUSTOMER_EMPTY,
                    HttpStatusCode.OK,
                    Headers.build {
                        append(HttpHeaders.ContentType, "application/json")
                    },
                )
            }
        val api = ApiClient(baseUrl, "pk_abc", engine)
        runCatching { api.customerInfo("\$anon_abc") }
        return urls
    }

    @Test
    fun theAddressIsTheBaseAddressAndThePathWhateverTheFormOfTheBase() =
        runTest {
            val expected =
                mapOf(
                    "http://localhost:8080" to "http://localhost:8080/v1/subscribers/\$anon_abc",
                    "http://localhost:8080/" to "http://localhost:8080/v1/subscribers/\$anon_abc",
                    "https://backend.example.com" to "https://backend.example.com/v1/subscribers/\$anon_abc",
                    "https://backend.example.com/" to "https://backend.example.com/v1/subscribers/\$anon_abc",
                    "https://backend.example.com/kuotakit" to
                        "https://backend.example.com/kuotakit/v1/subscribers/\$anon_abc",
                    "https://backend.example.com/kuotakit/" to
                        "https://backend.example.com/kuotakit/v1/subscribers/\$anon_abc",
                )
            expected.forEach { (base, url) -> assertEquals(listOf(url), urlsFor(base), "base address $base") }
        }
}
