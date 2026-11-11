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

package dev.pedrorau.kuotakit.sdk

/** The answers of the backend that the tests use, written as the backend writes them (docs/api-spec.md). */
internal object Samples {
    const val CUSTOMER_EMPTY =
        """{"app_user_id":"anon","request_date":"2026-11-01T12:00:00Z","entitlements":{},""" +
            """"active_subscriptions":[],"non_subscription_purchases":[]}"""

    const val CUSTOMER_PREMIUM =
        """{"app_user_id":"anon","request_date":"2026-11-01T12:00:00Z","entitlements":{"premium":{"is_active":true,""" +
            """"product_id":"premium_monthly","store":"play_store","period_type":"normal",""" +
            """"expires_at":"2026-12-01T12:00:00Z","will_renew":true,"billing_issue_detected_at":null}},""" +
            """"active_subscriptions":["premium_monthly"],"non_subscription_purchases":[]}"""

    const val OFFERINGS =
        """{"current":"default","offerings":[{"id":"default","packages":[""" +
            """{"id":"monthly","products":{"play_store":"premium_monthly","app_store":"com.acme.premium.monthly"}},""" +
            """{"id":"annual","products":{"play_store":"premium_annual","app_store":null}}]}]}"""

    /** One answer per row of the table "Errors": the status, the code and the body. */
    val ERRORS: Map<Int, String> =
        mapOf(
            400 to error("invalid_payload"),
            401 to error("unauthorized"),
            402 to error("purchase_invalid"),
            409 to error("token_already_linked"),
            429 to error("rate_limited"),
            500 to error("internal_error"),
            502 to error("store_unavailable"),
        )

    fun error(code: String) = """{"error":{"code":"$code","message":"a message of the backend"}}"""
}
