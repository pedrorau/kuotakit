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

/** One subscription bought by [user]: Google is told what it will say, then the app sends the receipt. */
internal class Bought(
    val user: String,
    val token: String,
    val reply: Reply,
)

@Suppress("LongParameterList") // the fields of Google's answer that a scenario varies
internal fun Context.subscribe(
    user: String = freshUser(),
    token: String = freshToken(),
    productId: String = "premium_monthly",
    state: String = "ACTIVE",
    expiry: String? = inDays(ACTIVE_DAYS),
    account: String? = accountIdOf(user),
    acknowledged: Boolean = false,
    autoRenew: Boolean = true,
    phase: String = "basePrice",
    linked: String? = null,
): Bought {
    scriptSubscription(
        token,
        GoogleJson.subscription(state, productId, expiry, account, acknowledged, autoRenew, phase, linked),
    )
    return Bought(user, token, submit(user, productId, token))
}

@Suppress("LongParameterList")
internal fun Context.buyOneTime(
    user: String = freshUser(),
    token: String = freshToken(),
    state: String = "PURCHASED",
    refundable: Int? = 1,
    account: String? = accountIdOf(user),
): Bought {
    scriptOneTime(token, GoogleJson.oneTime(state, "lifetime_unlock", refundable, account))
    return Bought(user, token, submit(user, "lifetime_unlock", token))
}

internal const val ACTIVE_DAYS = 30L
