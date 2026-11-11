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

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

private fun Context.cancelledUntilItsEnd() {
    val bought = subscribe(state = "CANCELED", autoRenew = false)
    bought.reply.expect(Status.OK, null, "a cancelled subscription")
    val premium = info(bought.user).entitlement("premium")
    expectEqual(true, info(bought.user).isActive("premium"), "access until the end of a cancelled subscription")
    expectEqual(
        false,
        premium?.getValue("will_renew")?.jsonPrimitive?.boolean,
        "will_renew of a cancelled subscription",
    )
}

private fun Context.pendingAndUnknown() {
    val user = freshUser()
    listOf("PENDING", "PENDING_PURCHASE_CANCELED", "SOMETHING_NEW").forEach { state ->
        subscribe(user = user, state = state)
            .reply
            .expect(Status.PURCHASE_INVALID, "purchase_invalid", "a subscription $state")
    }
    expectNothing(user, "after refused subscriptions")
    expectEqual(emptyList<String>(), acknowledgements(), "a refused subscription is not acknowledged")
}

private fun Context.otherProduct() {
    val user = freshUser()
    val token = freshToken()
    scriptSubscription(token, GoogleJson.subscription(productId = "premium_annual", account = accountIdOf(user)))
    submit(
        user,
        "premium_monthly",
        token,
    ).expect(Status.PURCHASE_INVALID, "purchase_invalid", "a product that does not match")
    expectNothing(user, "after a receipt for another product")
}

private fun Context.alreadyAcknowledged() {
    val bought = subscribe(acknowledged = true)
    bought.reply.expect(Status.OK, null, "a subscription Google already acknowledged")
    expectEqual(true, info(bought.user).isActive("premium"), "access")
    expectEqual(emptyList<String>(), acknowledgements(), "an acknowledged subscription is not acknowledged again")
}

private fun Context.failingAcknowledgement() {
    val user = freshUser()
    val token = freshToken()
    fake.answer(
        "POST",
        GoogleJson.acknowledgePath("premium_monthly", token),
        Status.INTERNAL_ERROR,
        GoogleJson.error(Status.INTERNAL_ERROR),
    )
    subscribe(user = user, token = token)
        .reply
        .expect(Status.BAD_GATEWAY, "store_unavailable", "a receipt whose acknowledgement fails")
    expectNothing(user, "after a failed acknowledgement")
    fake.reset()
    scriptSubscription(token, GoogleJson.subscription(account = accountIdOf(user)))
    submit(user, "premium_monthly", token).expect(Status.OK, null, "the same receipt once Google acknowledges")
    expectEqual(true, info(user).isActive("premium"), "access after the retry")
    expectEqual(
        listOf("POST " + GoogleJson.acknowledgePath("premium_monthly", token)),
        acknowledgements(),
        "what Google was asked to acknowledge after the retry",
    )
}

/** Level 2, subscriptions the backend refuses, or fails, or does not acknowledge twice. */
internal fun level2Subscriptions2(): List<Scenario> =
    listOf(
        Scenario("L2-06", Level.TWO) { cancelledUntilItsEnd() },
        Scenario("L2-07", Level.TWO) { pendingAndUnknown() },
        Scenario("L2-08", Level.TWO) { otherProduct() },
        Scenario("L2-09", Level.TWO) { alreadyAcknowledged() },
        Scenario("L2-10", Level.TWO) { failingAcknowledgement() },
    )
