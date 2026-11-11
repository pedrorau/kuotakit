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

/** What the suite and the backend under test agree on. Nothing here is a secret: the keys are for this test only. */
internal object Fixture {
    const val PACKAGE_NAME = "com.example.conformance"
    const val APP_KEY = "pk_conformance_app_key_for_tests_only"
    const val SECRET_KEY = "sk_conformance_secret_key_for_tests_only"
    const val PUBSUB_AUDIENCE = "http://backend:8080/v1/webhooks/google"
    const val PUBSUB_EMAIL = "push@conformance.iam.gserviceaccount.com"
    const val FAKE_GOOGLE_PORT = 9000

    /** The `entitlements.yaml` the backend under test is started with (the fake Google writes it at start). */
    val ENTITLEMENTS_YAML =
        """
        entitlements:
          premium:
            products:
              play_store: [premium_monthly, premium_annual]
              app_store: [com.example.premium.monthly, com.example.premium.annual]
          lifetime:
            products:
              play_store: [lifetime_unlock]
        one_time_products:
          play_store: [lifetime_unlock]
        current_offering: default
        offerings:
          default:
            monthly: { play_store: premium_monthly, app_store: com.example.premium.monthly }
            annual: { play_store: premium_annual, app_store: com.example.premium.annual }
            lifetime: { play_store: lifetime_unlock }
        """.trimIndent() + "\n"

    /** The offerings that file describes, written again here so that the test does not read what it checks. */
    val OFFERINGS_JSON =
        """
        {"current":"default","offerings":[{"id":"default","packages":[
          {"id":"monthly","products":{"play_store":"premium_monthly","app_store":"com.example.premium.monthly"}},
          {"id":"annual","products":{"play_store":"premium_annual","app_store":"com.example.premium.annual"}},
          {"id":"lifetime","products":{"play_store":"lifetime_unlock","app_store":null}}]}]}
        """.trimIndent()
}
