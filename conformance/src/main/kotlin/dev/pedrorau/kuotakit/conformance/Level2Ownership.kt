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

private fun Context.withoutAccountId() {
    val bought = subscribe(account = null)
    bought.reply.expect(Status.PURCHASE_INVALID, "purchase_invalid", "a subscription with no account id")
    expectNothing(bought.user, "after a subscription with no account id")
    expectEqual(emptyList<String>(), acknowledgements(), "it is not acknowledged")
}

private fun Context.accountOfAnotherUser() {
    val bought = subscribe(account = accountIdOf(freshUser()))
    bought.reply.expect(Status.CONFLICT, "token_already_linked", "a subscription of another user")
    expectNothing(bought.user, "after a subscription of another user")
}

private fun Context.accountIdWrittenOtherwise() {
    val user = freshUser()
    val hash = accountIdOf(user)
    val written =
        mapOf("the plain app user id" to user, "uppercase" to hash.uppercase(), "with spaces around" to " $hash ")
    written.forEach { (name, id) ->
        subscribe(
            user = user,
            account = id,
        ).reply.expect(Status.CONFLICT, "token_already_linked", "an account id that is $name")
    }
    expectNothing(user, "after account ids that are not the hash")
}

private fun Context.oneTokenTwoUsers() {
    val token = freshToken()
    val first = subscribe(token = token)
    first.reply.expect(Status.OK, null, "the first user")
    val other = freshUser()
    submit(
        other,
        "premium_monthly",
        token,
    ).expect(Status.CONFLICT, "token_already_linked", "a second user with the same token")
    expectNothing(other, "the second user gets nothing")
    expectEqual(true, info(first.user).isActive("premium"), "the first user keeps the access")
}

private fun Context.upgrade() {
    val user = freshUser()
    val old = subscribe(user = user)
    old.reply.expect(Status.OK, null, "the old plan")
    val replacement = subscribe(user = user, productId = "premium_annual", linked = old.token)
    replacement.reply.expect(Status.OK, null, "the upgrade")
    val info = info(user)
    expectEqual("premium_annual", info.entitlement("premium")?.text("product_id"), "the plan that gives access")
    expectEqual(listOf("premium_annual"), info.activeSubscriptions(), "the old plan stops giving access")

    val kept = subscribe()
    val refused = subscribe(user = kept.user, productId = "premium_annual", state = "PENDING", linked = kept.token)
    refused.reply.expect(Status.PURCHASE_INVALID, "purchase_invalid", "an upgrade whose payment is pending")
    expectEqual(
        listOf("premium_monthly"),
        info(kept.user).activeSubscriptions(),
        "a refused upgrade leaves the old plan",
    )
}

private fun Context.deferredReplacement() {
    val old = subscribe()
    val deferred = subscribe(user = old.user, productId = "premium_annual", expiry = null, linked = old.token)
    deferred.reply.expect(Status.PURCHASE_INVALID, "purchase_invalid", "a replacement with no end date")
    expectEqual(listOf("premium_monthly"), info(old.user).activeSubscriptions(), "the old plan is left alone")
}

/** Level 2, whose purchase it is and what replaces what. */
internal fun level2Ownership(): List<Scenario> =
    listOf(
        Scenario("L2-15", Level.TWO) { withoutAccountId() },
        Scenario("L2-16", Level.TWO) { accountOfAnotherUser() },
        Scenario("L2-17", Level.TWO) { accountIdWrittenOtherwise() },
        Scenario("L2-18", Level.TWO) { oneTokenTwoUsers() },
        Scenario("L2-19", Level.TWO) { upgrade() },
        Scenario("L2-37", Level.TWO) { deferredReplacement() },
    )
