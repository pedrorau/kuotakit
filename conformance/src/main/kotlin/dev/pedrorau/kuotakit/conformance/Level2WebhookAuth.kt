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

private const val AN_HOUR = 3600

private fun Context.untrustedPushes() {
    val bought = subscribe()
    val refund = Push.of(Push.voided(bought.token))
    val untrusted: Map<String, String?> =
        mapOf(
            "no proof" to null,
            "text that is not a token" to "not-a-token",
            "another audience" to pushToken("audience" to "http://other.example.com/hook"),
            "another service account" to pushToken("email" to "other@conformance.iam.gserviceaccount.com"),
            "an unverified email" to pushToken("email_verified" to false),
            "another issuer" to pushToken("issuer" to "https://issuer.example.com"),
            "an expired token" to pushToken("expires_in_seconds" to -AN_HOUR),
            "a bad signature" to pushToken("corrupt_signature" to true),
            "no signature (alg none)" to pushToken("algorithm" to "none"),
        )
    untrusted.forEach { (name, token) ->
        webhook(refund, token).expect(Status.UNAUTHORIZED, "unauthorized", "a push with $name")
    }
    expectEqual(true, info(bought.user).isActive("premium"), "no untrusted push takes the access away")
}

private fun Context.testMessage() {
    webhook(Push.of(Push.test())).expect(Status.NO_CONTENT, null, "a test message")
}

private fun Context.unreadableAndUnreachable() {
    listOf("not json", "{}", """{"message":{"data":"!!!not base64","messageId":"m1"}}""").forEach { body ->
        webhook(body).expect(Status.NO_CONTENT, null, "a push with a body that is not a notification: $body")
    }
    val bought = subscribe()
    val token = pushToken()
    fake.answer("GET", "/oauth2/v1/certs", Status.INTERNAL_ERROR, "")
    fake.answer("GET", "/oauth2/v3/certs", Status.INTERNAL_ERROR, "")
    webhook(Push.of(Push.voided(bought.token)), token)
        .expect(Status.BAD_GATEWAY, "store_unavailable", "a push while Google's signing keys cannot be fetched")
    expectEqual(true, info(bought.user).isActive("premium"), "a push that could not be checked changes nothing")
}

/** Level 2, who may push to the webhook and what an unreadable push does. */
internal fun level2WebhookAuth(): List<Scenario> =
    listOf(
        Scenario("L2-24", Level.TWO) { untrustedPushes() },
        Scenario("L2-25", Level.TWO) { testMessage() },
        Scenario("L2-33", Level.TWO) { unreadableAndUnreachable() },
    )
