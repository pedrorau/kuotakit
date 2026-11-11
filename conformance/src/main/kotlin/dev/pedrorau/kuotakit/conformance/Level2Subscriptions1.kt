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

private fun Context.validSubscription() {
    val expiry = inDays(ACTIVE_DAYS)
    val bought = subscribe(expiry = expiry)
    bought.reply.expect(Status.OK, null, "a valid subscription")
    val info = info(bought.user)
    val premium = info.entitlement("premium") ?: throw ScenarioFailure("no entitlement 'premium': $info")
    expectEqual(true, info.isActive("premium"), "is_active")
    expectEqual("premium_monthly", premium.text("product_id"), "product_id")
    expectEqual("play_store", premium.text("store"), "store")
    expectEqual("normal", premium.text("period_type"), "period_type")
    expectSameMoment(expiry, premium.text("expires_at"), "expires_at")
    expectEqual(true, premium.getValue("will_renew").jsonPrimitive.boolean, "will_renew")
    expectEqual(listOf("premium_monthly"), info.activeSubscriptions(), "the active subscriptions")
    expectEqual(
        listOf("POST " + GoogleJson.acknowledgePath("premium_monthly", bought.token)),
        acknowledgements(),
        "what Google was asked to acknowledge",
    )
}

private fun Context.trialAndIntroductoryPrice() {
    mapOf("freeTrial" to "trial", "introductoryPrice" to "intro").forEach { (phase, period) ->
        val bought = subscribe(phase = phase)
        bought.reply.expect(Status.OK, null, "a subscription in its $period")
        expectEqual(period, info(bought.user).entitlement("premium")?.text("period_type"), "period_type of the $phase")
        expectEqual(true, info(bought.user).isActive("premium"), "access during the $period")
    }
}

private fun Context.expiredSubscriptions() {
    val cases =
        mapOf(
            "reported expired by Google" to ("EXPIRED" to inDays(-1)),
            "active by state but past its end date" to ("ACTIVE" to inMinutes(-1)),
        )
    cases.forEach { (name, case) ->
        val bought = subscribe(state = case.first, expiry = case.second)
        bought.reply.expect(Status.OK, null, "a subscription $name")
        expectEqual(false, info(bought.user).isActive("premium"), "access of a subscription $name")
        expectEqual(emptyList<String>(), info(bought.user).activeSubscriptions(), "active subscriptions of one $name")
        expectEqual(emptyList<String>(), acknowledgements(), "an expired subscription is not acknowledged")
    }
    val lasting = subscribe(expiry = inMinutes(MINUTES_LEFT))
    expectEqual(true, info(lasting.user).isActive("premium"), "access one hour before the end")
}

private fun Context.gracePeriod() {
    val bought = subscribe(state = "IN_GRACE_PERIOD")
    bought.reply.expect(Status.OK, null, "a subscription in its grace period")
    val premium = info(bought.user).entitlement("premium")
    expectEqual(true, info(bought.user).isActive("premium"), "access in the grace period")
    expectEqual(true, premium?.text("billing_issue_detected_at") != null, "the billing problem is dated")
}

private fun Context.holdAndPause() {
    listOf("ON_HOLD", "PAUSED").forEach { state ->
        val bought = subscribe(state = state)
        bought.reply.expect(Status.OK, null, "a subscription $state")
        expectEqual(false, info(bought.user).isActive("premium"), "access of a subscription $state")
        scriptSubscription(bought.token, GoogleJson.subscription("ACTIVE", account = accountIdOf(bought.user)))
        submit(bought.user, "premium_monthly", bought.token).expect(Status.OK, null, "the receipt again, active")
        expectEqual(true, info(bought.user).isActive("premium"), "access once Google says $state is over")
    }
}

private const val MINUTES_LEFT = 60L

/** Level 2, subscriptions that are bought, ending, in trouble or paused. */
internal fun level2Subscriptions1(): List<Scenario> =
    listOf(
        Scenario("L2-01", Level.TWO) { validSubscription() },
        Scenario("L2-02", Level.TWO) { trialAndIntroductoryPrice() },
        Scenario("L2-03", Level.TWO) { expiredSubscriptions() },
        Scenario("L2-04", Level.TWO) { gracePeriod() },
        Scenario("L2-05", Level.TWO) { holdAndPause() },
    )
