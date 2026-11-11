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

private const val OLD = "premium_annual"
private const val NEW = "premium_monthly"
private const val CHANGE_DAYS = 20L

/** The user has the old plan and changes to the new one with a deferred replacement. */
private class Change(
    val user: String,
    val oldToken: String,
    val newToken: String,
    val oldEnds: String,
)

private fun Context.start(): Change {
    val bought = subscribe(productId = OLD, expiry = inDays(CHANGE_DAYS), acknowledged = true)
    check(bought.reply.status == Status.OK) { "the old plan could not be bought: ${bought.reply}" }
    return Change(bought.user, bought.token, freshToken(), inDays(CHANGE_DAYS))
}

@Suppress("LongParameterList") // the fields of Google's answer that a scenario varies
private fun Context.scriptChange(
    change: Change,
    oldEnds: String = change.oldEnds,
    newEnds: String? = null,
    account: String? = accountIdOf(change.user),
    acknowledged: Boolean = false,
    newOwned: Boolean = false,
) = scriptSubscription(
    change.newToken,
    GoogleJson.deferred(OLD, oldEnds, NEW, newEnds, account, change.oldToken, acknowledged, newOwned),
)

private fun Context.deferredChangeIsAccepted() {
    val change = start()
    scriptChange(change)

    submit(change.user, NEW, change.newToken).expect(Status.OK, null, "the receipt of a deferred plan change")

    val info = info(change.user)
    val premium = info.entitlement("premium")
    expectEqual(true, info.isActive("premium"), "the old plan keeps giving access")
    expectEqual(OLD, premium?.text("product_id"), "the plan the user has until the change takes effect")
    expectSameMoment(change.oldEnds, premium?.text("expires_at"), "the end of the old plan")
    expectEqual(false, premium?.getValue("will_renew")?.jsonPrimitive?.boolean, "the old plan does not renew")
    val acknowledgeOnce = listOf("POST " + GoogleJson.acknowledgePath(OLD, change.newToken))
    expectEqual(
        acknowledgeOnce,
        acknowledgements().filter {
            change.newToken in it
        },
        "acknowledged once, as the plan the user has",
    )
    // Naming the old product in the receipt gives the same, and nothing is acknowledged again once Google says it is.
    scriptChange(change, acknowledged = true)
    submit(change.user, OLD, change.newToken).expect(Status.OK, null, "the same receipt naming the old product")
    expectEqual(OLD, info(change.user).entitlement("premium")?.text("product_id"), "still the old plan")
    expectEqual(acknowledgeOnce, acknowledgements().filter { change.newToken in it }, "still acknowledged only once")
}

private fun Context.newPlanTakesEffect() {
    val change = start()
    scriptChange(change)
    submit(change.user, NEW, change.newToken).expect(Status.OK, null, "the receipt of a deferred plan change")

    val newEnds = inDays(ACTIVE_DAYS)
    scriptChange(change, oldEnds = inDays(-1), newEnds = newEnds, acknowledged = true, newOwned = true)
    webhook(
        Push.of(Push.subscription(change.newToken)),
    ).expect(Status.NO_CONTENT, null, "the renewal that starts the new plan")

    val premium = info(change.user).entitlement("premium")
    expectEqual(true, info(change.user).isActive("premium"), "the new plan gives access")
    expectEqual(NEW, premium?.text("product_id"), "the plan the user has once the change took effect")
    expectSameMoment(newEnds, premium?.text("expires_at"), "the end of the new plan")
    expectEqual(true, premium?.getValue("will_renew")?.jsonPrimitive?.boolean, "the new plan renews")
    expectEqual(listOf(NEW), info(change.user).activeSubscriptions(), "only the new plan is active")
    expectEqual(
        1,
        acknowledgements().count { change.newToken in it },
        "the renewal does not acknowledge the purchase again",
    )
}

private fun Context.unreadableChangeIsRefused() {
    val change = start()
    // The old plan ended and the new item is not owned yet (Google gives it no order), even if it carries an end date.
    scriptChange(change, oldEnds = inDays(-1), newEnds = inDays(ACTIVE_DAYS), newOwned = false)

    submit(
        change.user,
        NEW,
        change.newToken,
    ).expect(Status.PURCHASE_INVALID, "purchase_invalid", "a change that cannot be read")

    expectEqual(listOf(OLD), info(change.user).activeSubscriptions(), "the old plan is left alone")
    expectEqual(
        emptyList<String>(),
        acknowledgements().filter {
            change.newToken in it
        },
        "the refused purchase is not acknowledged",
    )
}

private fun Context.deferredChangeFollowsOwnership() {
    val withoutAccount = start()
    scriptChange(withoutAccount, account = null)
    submit(withoutAccount.user, NEW, withoutAccount.newToken)
        .expect(Status.PURCHASE_INVALID, "purchase_invalid", "a deferred change with no account id")

    val another = start()
    scriptChange(another, account = accountIdOf(freshUser()))
    submit(another.user, NEW, another.newToken)
        .expect(Status.CONFLICT, "token_already_linked", "a deferred change of another user")

    listOf(withoutAccount, another).forEach {
        expectEqual(listOf(OLD), info(it.user).activeSubscriptions(), "the old plan is left alone")
    }
}

/** Level 2, a plan change made with `ReplacementMode.DEFERRED` (docs/adr/0020). */
internal fun level2Deferred(): List<Scenario> =
    listOf(
        Scenario("L2-38", Level.TWO) { deferredChangeIsAccepted() },
        Scenario("L2-39", Level.TWO) { newPlanTakesEffect() },
        Scenario("L2-40", Level.TWO) { unreadableChangeIsRefused() },
        Scenario("L2-41", Level.TWO) { deferredChangeFollowsOwnership() },
    )
