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

private val NOT_A_PURCHASE = listOf(Status.BAD_REQUEST, Status.NOT_FOUND, GONE)
private val TROUBLE =
    listOf(Status.UNAUTHORIZED, FORBIDDEN, Status.TOO_MANY_REQUESTS, Status.INTERNAL_ERROR, UNAVAILABLE)

private const val GONE = 410
private const val FORBIDDEN = 403
private const val UNAVAILABLE = 503

private fun Context.readFailsAs(
    statuses: List<Int>,
    expected: Int,
    code: String,
) {
    statuses.forEach { status ->
        val user = freshUser()
        val token = freshToken()
        scriptSubscription(token, GoogleJson.error(status), status)
        submit(
            user,
            "premium_monthly",
            token,
        ).expect(expected, code, "Google answers $status when reading a subscription")
        val oneTime = freshToken()
        scriptOneTime(oneTime, GoogleJson.error(status), status)
        submit(
            user,
            "lifetime_unlock",
            oneTime,
        ).expect(expected, code, "Google answers $status when reading a one-time purchase")
        expectNothing(user, "after Google answered $status")
    }
}

private fun Context.googleSaysNotAPurchase() = readFailsAs(NOT_A_PURCHASE, Status.PURCHASE_INVALID, "purchase_invalid")

private fun Context.googleInTrouble() = readFailsAs(TROUBLE, Status.BAD_GATEWAY, "store_unavailable")

private fun Context.googleRefusesConnections() {
    val user = freshUser()
    val token = freshToken()
    scriptSubscription(token, GoogleJson.subscription(account = accountIdOf(user)))
    fake.down()
    try {
        submit(
            user,
            "premium_monthly",
            token,
        ).expect(Status.BAD_GATEWAY, "store_unavailable", "Google refuses the connection")
    } finally {
        fake.up()
    }
    expectNothing(user, "after a refused connection")
    submit(user, "premium_monthly", token).expect(Status.OK, null, "the same receipt once Google is back")
    expectEqual(true, info(user).isActive("premium"), "access once Google is back")
}

private fun Context.otherPackage() {
    val user = freshUser()
    val token = freshToken()
    backend
        .post("/v1/subscribers/$user/receipts", receipt(token, packageName = "com.other.app"))
        .expect(Status.BAD_REQUEST, "invalid_payload", "a receipt for another package")
    expectEqual(emptyList<String>(), reads(), "Google is not asked about another app's purchase")
    expectNothing(user, "after a receipt for another package")
}

/** Level 2, what Google can say or do wrong. */
internal fun level2Failures(): List<Scenario> =
    listOf(
        Scenario("L2-20", Level.TWO) { googleSaysNotAPurchase() },
        Scenario("L2-21", Level.TWO) { googleInTrouble() },
        Scenario("L2-22", Level.TWO) { googleRefusesConnections() },
        Scenario("L2-23", Level.TWO) { otherPackage() },
    )
