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

class ClientAddressTest :
    StringSpec({
        "without trust the connection is the client, whatever the header says" {
            clientAddress("10.0.0.1", listOf("6.6.6.6"), false) shouldBe "10.0.0.1"
            clientAddress("10.0.0.1", emptyList(), false) shouldBe "10.0.0.1"
        }

        "with trust the last value is the client: it is the one the proxy added" {
            clientAddress("10.0.0.1", listOf("203.0.113.7"), true) shouldBe "203.0.113.7"
            clientAddress("10.0.0.1", listOf("6.6.6.6, 203.0.113.7"), true) shouldBe "203.0.113.7"
            clientAddress("10.0.0.1", listOf("6.6.6.6,203.0.113.7 "), true) shouldBe "203.0.113.7"
        }

        "a proxy that adds its own header line instead of merging is still read at its end" {
            clientAddress("10.0.0.1", listOf("6.6.6.6", "203.0.113.7"), true) shouldBe "203.0.113.7"
            clientAddress("10.0.0.1", listOf("6.6.6.6, 7.7.7.7", "8.8.8.8, 203.0.113.7"), true) shouldBe "203.0.113.7"
        }

        "with trust but no usable header the connection is used" {
            listOf(
                emptyList(),
                listOf(""),
                listOf("  "),
                listOf("6.6.6.6,"),
                listOf("not an address"),
                listOf("1.2.3.4:5678"),
                listOf("999.1.1.1.1"),
                listOf("deadbeef"),
                listOf("x".repeat(9000)),
            ).forEach { lines ->
                clientAddress("10.0.0.1", lines, true) shouldBe "10.0.0.1"
            }
        }

        "an IPv6 address counts as its /64 network" {
            val a = clientAddress("10.0.0.1", listOf("2001:db8:1:2:aaaa:bbbb:cccc:dddd"), true)
            val b = clientAddress("10.0.0.1", listOf("2001:db8:1:2:1:2:3:4"), true)
            val other = clientAddress("10.0.0.1", listOf("2001:db8:1:3:1:2:3:4"), true)
            a shouldBe b
            (a == other) shouldBe false
            a shouldBe "v6:20010db800010002"
        }

        "the connection address is grouped the same way, and the spellings of one IPv4 address are one" {
            clientAddress("2001:db8:1:2:aaaa:bbbb:cccc:dddd", emptyList(), false) shouldBe "v6:20010db800010002"
            clientAddress("::ffff:203.0.113.7", emptyList(), false) shouldBe "203.0.113.7"
            clientAddress("203.0.113.7", emptyList(), false) shouldBe "203.0.113.7"
        }

        "something that is not an address is kept as it is, never looked up" {
            clientAddress("localhost", emptyList(), false) shouldBe "localhost"
        }
    })
