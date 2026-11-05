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

package dev.pedrorau.kuotakit.backend.domain

/** The store says this purchase is not valid, or not complete yet (HTTP 402, `purchase_invalid`). */
class PurchaseInvalidException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The store could not be asked, or did not answer as expected (HTTP 502, `store_unavailable`). Access is never
 * granted on this failure (ADR 0003).
 */
class StoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The receipt itself is malformed or not meant for this backend (HTTP 400, `invalid_payload`). */
class InvalidReceiptException(
    message: String,
) : RuntimeException(message)

/** What arrived is not a Pub/Sub push from Google Play at all (HTTP 400, `invalid_payload`). */
class InvalidNotificationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
