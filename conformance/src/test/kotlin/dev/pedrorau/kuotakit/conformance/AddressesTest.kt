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
import io.kotest.matchers.shouldBe

/**
 * The runner makes one [Backend] per scenario. A scenario that floods one address (the request limits) must never
 * leave the budget of an address that a later scenario is given, so no address is ever handed out twice in a run.
 */
class AddressesTest :
    StringSpec({
        "no two scenarios of a run get the same address, however many there are" {
            val contract = Contract.bundled()
            val addresses = List(3_000) { Backend("http://x", contract) }.map { it.freshAddress() }
            addresses.toSet().size shouldBe addresses.size
        }
    })
