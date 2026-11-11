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

import dev.pedrorau.kuotakit.contracts.ErrorCode
import dev.pedrorau.kuotakit.contracts.ErrorResponse
import dev.pedrorau.kuotakit.contracts.kuotaKitJson

/**
 * Turns an error answer of the backend into the exception of the table "Errors" of `docs/api-spec.md`. The code of the
 * body decides, not only the status: an answer that is not the error body of the contract is [KuotaKitException.Unexpected],
 * and so is a code this SDK does not know.
 */
internal fun mapFailure(
    status: Int,
    retryAfter: String?,
    body: String,
): KuotaKitException {
    val error =
        runCatching { kuotaKitJson.decodeFromString(ErrorResponse.serializer(), body).error }
            .getOrElse { return KuotaKitException.Unexpected("The backend answered $status without an error body") }
    val message = error.message
    return when (error.code) {
        ErrorCode.UNAUTHORIZED -> {
            KuotaKitException.Unauthorized(message)
        }

        ErrorCode.INVALID_PAYLOAD -> {
            KuotaKitException.InvalidRequest(message)
        }

        ErrorCode.PURCHASE_INVALID -> {
            KuotaKitException.PurchaseInvalid(message)
        }

        ErrorCode.TOKEN_ALREADY_LINKED -> {
            KuotaKitException.TokenAlreadyLinked(message)
        }

        ErrorCode.RATE_LIMITED -> {
            KuotaKitException.RateLimited(retryAfter?.trim()?.toIntOrNull(), message)
        }

        ErrorCode.STORE_UNAVAILABLE -> {
            KuotaKitException.StoreUnavailable(message)
        }

        ErrorCode.INTERNAL_ERROR -> {
            KuotaKitException.Unexpected(message)
        }

        ErrorCode.UNKNOWN -> {
            KuotaKitException.Unexpected(
                "The backend answered $status with an unknown error: $message",
            )
        }
    }
}
