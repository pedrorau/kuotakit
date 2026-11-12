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

package dev.pedrorau.kuotakit.sandbox

import dev.pedrorau.kuotakit.sdk.KuotaKitException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorTextTest {
    private val secret = "anon_a_secret_user_id http://secret-host:8080"

    private val all: List<KuotaKitException> =
        listOf(
            KuotaKitException.NotConfigured(),
            KuotaKitException.InvalidConfiguration(secret),
            KuotaKitException.Unauthorized(secret),
            KuotaKitException.InvalidRequest(secret),
            KuotaKitException.PurchaseInvalid(secret),
            KuotaKitException.TokenAlreadyLinked(secret),
            KuotaKitException.RateLimited(7, secret),
            KuotaKitException.RateLimited(null, secret),
            KuotaKitException.StoreUnavailable(secret),
            KuotaKitException.Network(secret, RuntimeException(secret)),
            KuotaKitException.Unexpected(secret, RuntimeException(secret)),
        )

    @Test
    fun `every failure is described with a text of the app, never with what the SDK or the network said`() {
        all.forEach { error ->
            val text = error.describe()
            assertTrue(text.isNotBlank())
            assertFalse(text.contains("secret"), text)
        }
    }

    @Test
    fun `a rate limit says how long to wait when the backend said so`() {
        assertTrue(KuotaKitException.RateLimited(7, "x").describe().contains("7 seconds"))
    }

    @Test
    fun `a refused purchase says that nothing was granted`() {
        assertTrue(KuotaKitException.PurchaseInvalid("x").describe().contains("nothing was granted"))
        assertTrue(KuotaKitException.TokenAlreadyLinked("x").describe().contains("nothing was granted"))
    }
}
