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

package dev.pedrorau.kuotakit.backend.config

import dev.pedrorau.kuotakit.contracts.Store
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val BASE =
    """
    entitlements:
      premium:
        products:
          play_store: [premium_monthly]
      lifetime:
        products:
          play_store: [lifetime_unlock, pack]
          app_store: [com.acme.lifetime]
    current_offering: default
    offerings:
      default:
        monthly: { play_store: premium_monthly }
    """.trimIndent()

private fun problem(text: String) =
    shouldThrow<ConfigException> {
        EntitlementsYaml.parse(text, "t.yaml")
    }.message.orEmpty()

class EntitlementsYamlOneTimeTest :
    StringSpec({
        "without one_time_products every product is a subscription" {
            val config = EntitlementsYaml.parse(BASE)
            config.oneTimeProducts shouldBe emptyMap()
            config.isOneTime(Store.PLAY_STORE, "lifetime_unlock") shouldBe false
        }

        "the one-time products are read per store" {
            val config =
                EntitlementsYaml.parse(
                    BASE +
                        "\none_time_products:\n  play_store: [lifetime_unlock, pack]\n" +
                        "  app_store: [com.acme.lifetime]\n",
                )
            config.oneTimeProducts shouldBe
                mapOf(
                    Store.PLAY_STORE to setOf("lifetime_unlock", "pack"),
                    Store.APP_STORE to setOf("com.acme.lifetime"),
                )
            config.isOneTime(Store.PLAY_STORE, "pack") shouldBe true
            config.isOneTime(Store.PLAY_STORE, "premium_monthly") shouldBe false
            config.isOneTime(Store.APP_STORE, "pack") shouldBe false
            config.isOneTime(Store.APP_STORE, "com.acme.lifetime") shouldBe true
        }

        "a one-time product that grants nothing is a typo and is reported where it is" {
            problem(BASE + "\none_time_products:\n  play_store: [lifetime_unlcok]\n") shouldBe
                "t.yaml:14:16: one-time product 'lifetime_unlcok' (play_store) is not part of any entitlement"
        }

        "a product of another store does not count" {
            problem(BASE + "\none_time_products:\n  app_store: [pack]\n") shouldContain "not part of any entitlement"
        }

        "a wrong shape is reported, not ignored" {
            problem(BASE + "\none_time_products:\n  play_store: []\n") shouldContain "is empty"
            problem(BASE + "\none_time_products:\n  mystery_store: [pack]\n") shouldContain
                "unknown store 'mystery_store'"
            problem(BASE + "\none_time_products: [pack]\n") shouldContain "must be a mapping"
            problem(BASE + "\none_time_products:\n  play_store: pack\n") shouldContain "must be a list"
        }
    })
