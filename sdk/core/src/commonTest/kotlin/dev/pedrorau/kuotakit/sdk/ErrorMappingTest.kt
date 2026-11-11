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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ErrorMappingTest {
    private fun map(
        status: Int,
        body: String = Samples.ERRORS.getValue(status),
        retryAfter: String? = null,
    ) = mapFailure(status, retryAfter, body)

    @Test
    fun everyRowOfTheTableOfErrorsMapsToItsException() {
        assertIs<KuotaKitException.InvalidRequest>(map(400))
        assertIs<KuotaKitException.Unauthorized>(map(401))
        assertIs<KuotaKitException.PurchaseInvalid>(map(402))
        assertIs<KuotaKitException.TokenAlreadyLinked>(map(409))
        assertIs<KuotaKitException.RateLimited>(map(429))
        assertIs<KuotaKitException.Unexpected>(map(500))
        assertIs<KuotaKitException.StoreUnavailable>(map(502))
    }

    @Test
    fun theMessageOfTheBackendIsKept() {
        assertEquals("a message of the backend", map(402).message)
    }

    @Test
    fun theWaitOfARateLimitIsReadFromRetryAfter() {
        assertEquals(7, assertIs<KuotaKitException.RateLimited>(map(429, retryAfter = "7")).retryAfterSeconds)
        assertEquals(7, assertIs<KuotaKitException.RateLimited>(map(429, retryAfter = " 7 ")).retryAfterSeconds)
        assertNull(assertIs<KuotaKitException.RateLimited>(map(429)).retryAfterSeconds)
        assertNull(assertIs<KuotaKitException.RateLimited>(map(429, retryAfter = "soon")).retryAfterSeconds)
    }

    @Test
    fun anAnswerThatIsNotTheErrorBodyIsUnexpectedWhateverTheStatus() {
        val bodies = listOf("", "not json", "{}", """{"error":{"code":"invalid_payload"}}""", "<html>502</html>")
        bodies.forEach { body ->
            listOf(400, 401, 402, 409, 429, 502).forEach { status ->
                assertIs<KuotaKitException.Unexpected>(map(status, body), "status $status with body '$body'")
            }
        }
    }

    @Test
    fun aCodeTheSdkDoesNotKnowIsUnexpectedNotAnyOtherException() {
        assertIs<KuotaKitException.Unexpected>(map(400, Samples.error("a_code_from_the_future")))
    }

    @Test
    fun theCodeDecidesNotTheStatus() {
        // A proxy could change the status; the contract of the body is what the SDK trusts.
        assertIs<KuotaKitException.PurchaseInvalid>(map(500, Samples.error("purchase_invalid")))
    }
}
