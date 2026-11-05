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

import dev.pedrorau.kuotakit.backend.domain.CONFIG
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files

private val VALID =
    """
    entitlements:
      premium:
        products:
          play_store: [premium_monthly, premium_annual]
          app_store: [com.acme.premium.monthly, com.acme.premium.annual]
      lifetime:
        products:
          play_store: [pro_lifetime]
    current_offering: default
    offerings:
      default:
        monthly: { play_store: premium_monthly, app_store: com.acme.premium.monthly }
        annual:  { play_store: premium_annual }
      sale: {}
    """.trimIndent()

private fun problem(yaml: String): String =
    shouldThrow<ConfigException> {
        EntitlementsYaml.parse(yaml, "test.yaml")
    }.message.orEmpty()

private fun replace(
    old: String,
    new: String,
): String {
    check(old in VALID) { "fixture changed: $old" }
    return VALID.replace(old, new)
}

class EntitlementsYamlTest :
    StringSpec({
        "the example of the API spec is read into the configuration, in order" {
            EntitlementsYaml.parse(VALID) shouldBe CONFIG
        }

        "a file is read from a path" {
            val file = Files.createTempFile("entitlements", ".yaml")
            Files.writeString(file, VALID)
            EntitlementsYaml.load(file) shouldBe CONFIG
        }

        "the example shipped in the repository is a valid configuration" {
            val config =
                EntitlementsYaml.load(
                    java.nio.file.Path
                        .of("../entitlements.example.yaml"),
                )
            config.currentOffering shouldBe "default"
            config.entitlements.keys shouldBe setOf("premium")
        }

        "a file that does not exist says so" {
            val missing = Files.createTempDirectory("missing").resolve("entitlements.yaml")
            shouldThrow<ConfigException> { EntitlementsYaml.load(missing) }.message shouldContain "does not exist"
        }

        "an empty file is reported" {
            problem("") shouldBe "test.yaml: the file is empty"
        }

        "invalid YAML is reported with the file name" {
            problem("entitlements: [unclosed").shouldStartWith("test.yaml: ")
        }

        "the top level must be a mapping with only the known keys, and the error points at the line" {
            problem("- just\n- a list") shouldBe "test.yaml:1:1: the file must be a mapping"
            problem(VALID + "\nextra: 1") shouldBe
                "test.yaml:15:1: unknown key 'extra' " +
                "(expected entitlements, current_offering, offerings, one_time_products)"
        }

        "each required key must be there" {
            problem(replace("current_offering: default\n", "")).shouldContain("missing 'current_offering'")
            problem(VALID.substringBefore("current_offering")).shouldContain("missing 'offerings'")
            problem(VALID.replace("entitlements:", "stuff:")).shouldContain("unknown key 'stuff'")
        }

        "the current offering must exist, and the error shows what exists" {
            problem(replace("current_offering: default", "current_offering: holiday")) shouldBe
                "test.yaml:9:19: current_offering 'holiday' is not one of the offerings (default, sale)"
        }

        "a store other than play_store and app_store is rejected, with the position of the key" {
            problem(replace("app_store: [com.acme.premium.monthly", "android: [com.acme.premium.monthly")) shouldBe
                "test.yaml:5:7: unknown store 'android' (use play_store or app_store)"
        }

        "an entitlement without products, or with an empty list, is rejected" {
            problem(
                replace("play_store: [pro_lifetime]", "play_store: []"),
            ).shouldContain("the product list of 'play_store' in 'lifetime' is empty")
            problem(
                replace("products:\n      play_store: [pro_lifetime]", "products: {}"),
            ).shouldContain("entitlement 'lifetime' has no products")
        }

        "unknown keys inside an entitlement are rejected" {
            problem(
                replace("  lifetime:\n    products:", "  lifetime:\n    price: 1\n    products:"),
            ).shouldContain("unknown key 'price' in entitlement 'lifetime'")
        }

        "a duplicate key is rejected instead of silently keeping the last one" {
            problem(replace("  sale: {}", "  default: {}")) shouldBe
                "test.yaml:14:3: duplicate key 'default' in offerings"
        }

        "a package must sell something" {
            problem(
                replace("annual:  { play_store: premium_annual }", "annual: {}"),
            ).shouldContain("package 'annual' of offering 'default' sells no product")
        }

        "a product sold in an offering must grant an entitlement, because a typo there loses revenue" {
            problem(replace("play_store: premium_annual }", "play_store: premium_anual }")).shouldContain(
                "product 'premium_anual' (play_store) of package 'annual' in offering 'default' " +
                    "is not part of any entitlement",
            )
        }

        "there must be at least one offering" {
            problem(
                "entitlements:\n  p:\n    products:\n      play_store: [a]\ncurrent_offering: x\nofferings: {}\n",
            ).shouldContain("there are no offerings")
        }

        "values must be of the right shape" {
            problem(replace("play_store: [pro_lifetime]", "play_store: pro_lifetime")).shouldContain("must be a list")
            problem(
                replace("current_offering: default", "current_offering: [a]"),
            ).shouldContain("current_offering must be a plain value")
        }
    })
