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

package dev.pedrorau.kuotakit.backend.web

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private val keys =
    ApiKeys(appKeys = listOf("pk_aaaaaaaaaaaaaaaa", "pk_bbbbbbbbbbbbbbbb"), secretKeys = listOf("sk_cccccccccccccccc"))

class ApiKeysTest :
    StringSpec({
        "each key is recognized as what it is" {
            keys.authenticate("pk_aaaaaaaaaaaaaaaa")?.kind shouldBe KeyKind.APP
            keys.authenticate("pk_bbbbbbbbbbbbbbbb")?.kind shouldBe KeyKind.APP
            keys.authenticate("sk_cccccccccccccccc")?.kind shouldBe KeyKind.SECRET
        }

        "anything else is not a key" {
            keys.authenticate("") shouldBe null
            keys.authenticate("pk_") shouldBe null
            keys.authenticate("pk_aaaaaaaaaaaaaaa") shouldBe null // one character short
            keys.authenticate("pk_aaaaaaaaaaaaaaaaX") shouldBe null // one character long
            keys.authenticate("PK_AAAAAAAAAAAAAAAA") shouldBe null // keys are case sensitive
            keys.authenticate("sk_aaaaaaaaaaaaaaaa") shouldBe null // the secret prefix with an app key body
            keys.authenticate(" pk_aaaaaaaaaaaaaaaa") shouldBe null
        }

        "with no keys configured nothing gets in" {
            ApiKeys(emptyList(), emptyList()).authenticate("pk_aaaaaaaaaaaaaaaa") shouldBe null
            ApiKeys(emptyList(), emptyList()).authenticate("") shouldBe null
        }
    })
