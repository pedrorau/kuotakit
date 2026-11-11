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

/**
 * Every way a call of the SDK can fail. The SDK never grants access when something goes wrong (ADR 0003): a failure
 * is always one of these, and the app decides what to tell the user.
 */
public sealed class KuotaKitException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** [KuotaKit.shared] was used before [KuotaKit.configure]. */
    public class NotConfigured : KuotaKitException("KuotaKit.configure must be called first")

    /** The configuration is not usable (for example a secret API key, which must never be in an app). */
    public class InvalidConfiguration(
        message: String,
    ) : KuotaKitException(message)

    /** The backend refused the API key (`401`). */
    public class Unauthorized(
        message: String,
    ) : KuotaKitException(message)

    /** The backend did not accept the request (`400`), or the package cannot be bought on this store. */
    public class InvalidRequest(
        message: String,
    ) : KuotaKitException(message)

    /** The store does not say the purchase is valid (`402`): no access is granted. */
    public class PurchaseInvalid(
        message: String,
    ) : KuotaKitException(message)

    /** The purchase belongs to another user (`409`). */
    public class TokenAlreadyLinked(
        message: String,
    ) : KuotaKitException(message)

    /** Too many requests (`429`): try again after [retryAfterSeconds], when the backend said how long. */
    public class RateLimited(
        public val retryAfterSeconds: Int?,
        message: String,
    ) : KuotaKitException(message)

    /** The store could not be reached by the backend (`502`), or billing is not available on the device. */
    public class StoreUnavailable(
        message: String,
    ) : KuotaKitException(message)

    /** The backend could not be reached. */
    public class Network(
        message: String,
        cause: Throwable? = null,
    ) : KuotaKitException(message, cause)

    /** Anything else: an error of the backend (`500`), or an answer the SDK does not understand. */
    public class Unexpected(
        message: String,
        cause: Throwable? = null,
    ) : KuotaKitException(message, cause)
}
