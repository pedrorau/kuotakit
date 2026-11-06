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

class ProxyHintTest :
    StringSpec({
        "it tells once when a forwarded address arrives and the proxy is not trusted" {
            val hint = ProxyHint()
            hint.shouldTell(listOf("203.0.113.7"), false) shouldBe true
            hint.shouldTell(listOf("203.0.113.7"), false) shouldBe false
            hint.shouldTell(listOf("198.51.100.1"), false) shouldBe false
        }

        "it says nothing when the proxy is trusted, or when no header comes" {
            val trusted = ProxyHint()
            trusted.shouldTell(listOf("203.0.113.7"), true) shouldBe false
            val plain = ProxyHint()
            plain.shouldTell(emptyList(), false) shouldBe false
            // a call with no header does not use up the one notice
            plain.shouldTell(listOf("203.0.113.7"), false) shouldBe true
        }
    })
