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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

private val contract = Contract.bundled()
private val json = mapOf("Content-Type" to listOf("application/json"))
private const val INFO =
    """{"app_user_id":"a","request_date":"2026-01-01T00:00:00Z","entitlements":{},"active_subscriptions":[],""" +
        """"non_subscription_purchases":[]}"""

class ContractTest :
    StringSpec({
        "an answer that matches the document has no violation" {
            contract.violations("GET", "/v1/subscribers/a", Reply(200, json, INFO)).shouldBeEmpty()
        }

        "a required field missing, or of the wrong type, is a violation" {
            val missing = INFO.replace(""","non_subscription_purchases":[]""", "")
            contract.violations("GET", "/v1/subscribers/a", Reply(200, json, missing)).shouldNotBeEmpty()
            val wrongType = INFO.replace(""""entitlements":{}""", """"entitlements":[]""")
            contract.violations("GET", "/v1/subscribers/a", Reply(200, json, wrongType)).shouldNotBeEmpty()
        }

        "what the document does not describe is no violation, but it is not described" {
            val plain = Reply(404, emptyMap(), "")
            contract.violations("GET", "/nothing", plain).shouldBeEmpty()
            contract.describes("GET", "/nothing", plain) shouldBe false
            contract.describes("GET", "/v1/subscribers/a", Reply(418, json, "{}")) shouldBe false
            contract.describes("GET", "/v1/subscribers/a", Reply(200, json, INFO)) shouldBe true
        }
    })
