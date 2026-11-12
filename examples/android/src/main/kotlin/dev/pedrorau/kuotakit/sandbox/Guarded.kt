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
import dev.pedrorau.kuotakit.sdk.PurchaseOutcome
import kotlin.coroutines.cancellation.CancellationException

/** The result of a call of the SDK: what it returned, or the text to show when it failed. */
internal sealed interface Guarded<out T> {
    data class Done<T>(
        val value: T,
    ) : Guarded<T>

    data class Failed(
        val message: String,
    ) : Guarded<Nothing>
}

/**
 * Runs a call of the SDK. The SDK throws only [KuotaKitException]; anything else (a bug or a failure of a library under
 * it) is also shown as a failure that granted nothing, instead of ending the app. A cancelled coroutine is never
 * swallowed.
 */
@Suppress("TooGenericExceptionCaught") // the app must not crash on what a billing library throws
internal suspend fun <T> guarded(call: suspend () -> T): Guarded<T> =
    try {
        Guarded.Done(call())
    } catch (e: KuotaKitException) {
        Guarded.Failed(e.describe())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Guarded.Failed("Something unexpected happened. Nothing was granted.")
    }

/** What an outcome of a purchase says, in words. It never says the user has access: the Plan shows that. */
internal fun PurchaseOutcome.words(): String =
    when (this) {
        is PurchaseOutcome.Success -> "The backend confirmed it. See the Plan tab."
        is PurchaseOutcome.AlreadyOwned -> "You already own it; the backend restored it. See the Plan tab."
        PurchaseOutcome.Cancelled -> "Cancelled."
        PurchaseOutcome.Pending -> "Payment pending: nothing is granted until Google Play confirms it."
    }
