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

package dev.pedrorau.kuotakit.backend.domain

import dev.pedrorau.kuotakit.contracts.Offering
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.contracts.StoreProducts
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class OfferingsTest :
    StringSpec({
        "the offerings response mirrors the configuration, in order" {
            CONFIG.toOfferingsResponse() shouldBe
                OfferingsResponse(
                    current = "default",
                    offerings =
                        listOf(
                            Offering(
                                "default",
                                listOf(
                                    OfferingPackage(
                                        "monthly",
                                        StoreProducts("premium_monthly", "com.acme.premium.monthly"),
                                    ),
                                    OfferingPackage("annual", StoreProducts("premium_annual", null)),
                                ),
                            ),
                            Offering("sale", emptyList()),
                        ),
                )
        }
    })
