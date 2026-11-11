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

private fun Context.validOneTime() {
    val bought = buyOneTime()
    bought.reply.expect(Status.OK, null, "a valid one-time purchase")
    val info = info(bought.user)
    expectEqual(true, info.isActive("lifetime"), "access")
    expectEqual(null, info.entitlement("lifetime")?.text("expires_at"), "a one-time purchase has no end date")
    expectEqual(listOf("lifetime_unlock"), info.oneTimeProducts(), "the one-time purchases")
    expectEqual(emptyList<String>(), info.activeSubscriptions(), "a one-time purchase is not a subscription")
    expectEqual(
        listOf("POST " + GoogleJson.oneTimeAcknowledgePath("lifetime_unlock", bought.token)),
        acknowledgements(),
        "what Google was asked to acknowledge",
    )
}

private fun Context.refundedOneTime() {
    val bought = buyOneTime(refundable = 0)
    bought.reply.expect(Status.OK, null, "a one-time purchase with nothing left to refund")
    val info = info(bought.user)
    expectEqual(false, info.isActive("lifetime"), "access of a refunded one-time purchase")
    expectEqual(emptyList<String>(), info.oneTimeProducts(), "a refunded purchase is not listed")
    expectEqual(emptyList<String>(), acknowledgements(), "a refunded purchase is not acknowledged")
}

private fun Context.unknownRefundableQuantity() {
    val bought = buyOneTime(refundable = null)
    bought.reply.expect(Status.PURCHASE_INVALID, "purchase_invalid", "a one-time purchase with no refundable quantity")
    expectNothing(bought.user, "after a purchase that does not say what is left to refund")
}

private fun Context.pendingAndCancelledOneTime() {
    listOf("PENDING", "CANCELLED").forEach { state ->
        val bought = buyOneTime(state = state)
        bought.reply.expect(Status.PURCHASE_INVALID, "purchase_invalid", "a one-time purchase $state")
        expectNothing(bought.user, "after a one-time purchase $state")
    }
    expectEqual(emptyList<String>(), acknowledgements(), "a refused purchase is not acknowledged")
}

private fun Context.oneTimeWithoutAccountId() {
    val bought = buyOneTime(account = null)
    bought.reply.expect(Status.PURCHASE_INVALID, "purchase_invalid", "a one-time purchase with no account id")
    expectNothing(bought.user, "after a one-time purchase with no account id")
    expectEqual(emptyList<String>(), acknowledgements(), "it is not acknowledged")
}

private fun Context.oneTimeOfAnotherUser() {
    val bought = buyOneTime(account = accountIdOf(freshUser()))
    bought.reply.expect(Status.CONFLICT, "token_already_linked", "a one-time purchase of another user")
    expectNothing(bought.user, "after a one-time purchase of another user")
}

/** Level 2, one-time purchases. */
internal fun level2OneTime(): List<Scenario> =
    listOf(
        Scenario("L2-11", Level.TWO) { validOneTime() },
        Scenario("L2-12", Level.TWO) { refundedOneTime() },
        Scenario("L2-13", Level.TWO) { unknownRefundableQuantity() },
        Scenario("L2-14", Level.TWO) { pendingAndCancelledOneTime() },
        Scenario("L2-35", Level.TWO) { oneTimeWithoutAccountId() },
        Scenario("L2-36", Level.TWO) { oneTimeOfAnotherUser() },
    )
