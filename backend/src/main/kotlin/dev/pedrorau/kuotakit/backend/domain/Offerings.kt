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
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.StoreProducts

/** The body of `GET /v1/offerings`. */
fun EntitlementsConfig.toOfferingsResponse(): OfferingsResponse =
    OfferingsResponse(
        current = currentOffering,
        offerings =
            offerings.values.map { offering ->
                Offering(
                    id = offering.id,
                    packages =
                        offering.packages.map { (id, definition) ->
                            OfferingPackage(
                                id = id,
                                products =
                                    StoreProducts(
                                        playStore = definition.products[Store.PLAY_STORE],
                                        appStore = definition.products[Store.APP_STORE],
                                    ),
                            )
                        },
                )
            },
    )
