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

/** What to tell the user about a failure. Static texts: nothing the SDK or the network said is shown as it came. */
internal fun KuotaKitException.describe(): String =
    when (this) {
        is KuotaKitException.NotConfigured -> {
            "Set the backend address and the public key in Settings; the app restarts to use them."
        }

        is KuotaKitException.InvalidConfiguration -> {
            "The backend address or the key are not usable. Check Settings."
        }

        is KuotaKitException.Unauthorized -> {
            "The backend does not accept this key. Check it in Settings."
        }

        is KuotaKitException.InvalidRequest -> {
            "The backend did not accept the request, or this product cannot be bought here."
        }

        is KuotaKitException.PurchaseInvalid -> {
            "Google Play does not confirm this purchase: nothing was granted."
        }

        is KuotaKitException.TokenAlreadyLinked -> {
            "That purchase belongs to another user: nothing was granted."
        }

        is KuotaKitException.RateLimited -> {
            retryAfterSeconds?.let { "Too many requests. Try again in $it seconds." }
                ?: "Too many requests. Try again later."
        }

        is KuotaKitException.StoreUnavailable -> {
            "Google Play (or the backend's connection to it) is not available now."
        }

        is KuotaKitException.Network -> {
            "The backend cannot be reached. Check the address and the connection."
        }

        is KuotaKitException.Unexpected -> {
            "Something unexpected happened. Nothing was granted."
        }
    }
