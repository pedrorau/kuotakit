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

private const val RENEWED_DAYS = 40L
private const val FIRST_DAYS = 10L

private fun Context.refund() {
    val bought = subscribe()
    webhook(Push.of(Push.voided(bought.token))).expect(Status.NO_CONTENT, null, "a refund")
    expectEqual(false, info(bought.user).isActive("premium"), "access after a refund")
    submit(bought.user, "premium_monthly", bought.token).expect(Status.OK, null, "the receipt sent again")
    expectEqual(
        false,
        info(bought.user).isActive("premium"),
        "a refund is final: the receipt does not bring access back",
    )
}

private fun Context.revocation() {
    val bought = subscribe()
    webhook(
        Push.of(Push.subscription(bought.token, NotificationType.REVOKED)),
    ).expect(Status.NO_CONTENT, null, "a revocation")
    expectEqual(false, info(bought.user).isActive("premium"), "access after a revocation")
    submit(bought.user, "premium_monthly", bought.token).expect(Status.OK, null, "the receipt sent again")
    expectEqual(false, info(bought.user).isActive("premium"), "a revocation is final too")
}

private fun Context.refundBeforeTheReceipt() {
    val user = freshUser()
    val token = freshToken()
    webhook(Push.of(Push.voided(token))).expect(Status.NO_CONTENT, null, "a refund of a purchase nobody sent yet")
    val bought = subscribe(user = user, token = token)
    bought.reply.expect(Status.OK, null, "the receipt that comes after the refund")
    expectEqual(false, info(user).isActive("premium"), "access of a purchase refunded before its receipt")
    expectEqual(emptyList<String>(), acknowledgements(), "a refunded purchase is not acknowledged")
}

private fun Context.duplicateMessage() {
    val bought = subscribe(expiry = inDays(FIRST_DAYS))
    scriptSubscription(
        bought.token,
        GoogleJson.subscription(expiry = inDays(RENEWED_DAYS), account = accountIdOf(bought.user), acknowledged = true),
    )
    val before = reads().size
    val push = Push.of(Push.subscription(bought.token), messageId = Push.messageId())
    webhook(push).expect(Status.NO_CONTENT, null, "the message")
    webhook(push).expect(Status.NO_CONTENT, null, "the same message again")
    expectEqual(1, reads().size - before, "how many times Google was asked about a message that came twice")
}

private fun Context.anotherApp() {
    val expiry = inDays(FIRST_DAYS)
    val bought = subscribe(expiry = expiry)
    val before = reads().size
    webhook(Push.of(Push.voided(bought.token).replace(Fixture.PACKAGE_NAME, "com.other.app")))
        .expect(Status.NO_CONTENT, null, "a refund for another app")
    webhook(Push.of(Push.subscription(bought.token, packageName = "com.other.app")))
        .expect(Status.NO_CONTENT, null, "a renewal for another app")
    expectEqual(0, reads().size - before, "Google is not asked about another app")
    expectEqual(true, info(bought.user).isActive("premium"), "another app's notification changes nothing")
}

private fun Context.renewal() {
    val bought = subscribe(expiry = inDays(FIRST_DAYS))
    val renewed = inDays(RENEWED_DAYS)
    scriptSubscription(
        bought.token,
        GoogleJson.subscription(expiry = renewed, account = accountIdOf(bought.user), acknowledged = true),
    )
    webhook(Push.of(Push.subscription(bought.token))).expect(Status.NO_CONTENT, null, "a renewal")
    expectSameMoment(
        renewed,
        info(bought.user).entitlement("premium")?.text("expires_at"),
        "the end date after the renewal",
    )
}

private fun Context.oneTimeNotifications() {
    val refunded = buyOneTime()
    webhook(Push.of(Push.voided(refunded.token, NotificationType.ONE_TIME_PRODUCT)))
        .expect(Status.NO_CONTENT, null, "a refund of a one-time purchase")
    expectEqual(false, info(refunded.user).isActive("lifetime"), "access after a one-time refund")
    expectEqual(emptyList<String>(), info(refunded.user).oneTimeProducts(), "a refunded purchase is not listed")

    val cancelled = buyOneTime()
    scriptOneTime(
        cancelled.token,
        GoogleJson.oneTime("CANCELLED", account = accountIdOf(cancelled.user), acknowledged = true),
    )
    webhook(
        Push.of(Push.oneTime(cancelled.token)),
    ).expect(Status.NO_CONTENT, null, "a cancellation of a one-time purchase")
    expectEqual(false, info(cancelled.user).isActive("lifetime"), "access after a one-time cancellation")
}

private fun Context.purchaseNobodySubmitted() {
    val user = freshUser()
    val token = freshToken()
    webhook(Push.of(Push.subscription(token))).expect(Status.NO_CONTENT, null, "a renewal of a purchase nobody sent")
    expectNothing(user, "after a notification for a purchase nobody sent")
    subscribe(user = user, token = token).reply.expect(Status.OK, null, "the receipt that comes later")
    expectEqual(true, info(user).isActive("premium"), "a receipt after an unknown notification is a new purchase")
    expectEqual(1, acknowledgements().size, "it is acknowledged once")
}

/** Level 2, what the notifications from Google Play do. */
internal fun level2WebhookEvents(): List<Scenario> =
    listOf(
        Scenario("L2-26", Level.TWO) { refund() },
        Scenario("L2-27", Level.TWO) { revocation() },
        Scenario("L2-28", Level.TWO) { refundBeforeTheReceipt() },
        Scenario("L2-29", Level.TWO) { duplicateMessage() },
        Scenario("L2-30", Level.TWO) { anotherApp() },
        Scenario("L2-31", Level.TWO) { renewal() },
        Scenario("L2-32", Level.TWO) { oneTimeNotifications() },
        Scenario("L2-34", Level.TWO) { purchaseNobodySubmitted() },
    )
