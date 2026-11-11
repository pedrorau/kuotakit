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

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val key = SigningKey.generate()

private fun <T> withFake(block: (FakeGoogle, String, FakeGoogleControl, Http) -> T): T =
    FakeGoogle(0, 0, key).use { fake ->
        val control = "http://127.0.0.1:${fake.controlPort}"
        block(fake, "http://127.0.0.1:${fake.port}", FakeGoogleControl(control), Http())
    }

class FakeGoogleControlTest :
    StringSpec({
        "the control API makes the same signed push token as the signer, for the spec it is given" {
            withFake { _, _, control, _ ->
                val token = control.pushToken(buildJsonObject { put("audience", "http://backend/hook") })
                token.split(".").size shouldBe 3
                control.pushToken(buildJsonObject { put("algorithm", "none") }).endsWith(".") shouldBe true
            }
        }

        "while Google is down its port refuses connections, the control API answers, and it comes back" {
            withFake { _, google, control, http ->
                http.send("GET", "$google/health").status shouldBe Status.OK
                control.down()
                shouldThrowAny { http.send("GET", "$google/health") }
                control.calls() shouldBe emptyList()
                control.up()
                http.send("GET", "$google/health").status shouldBe Status.OK
            }
        }

        "a reset brings Google back up, so a scenario that failed while it was down cannot leave it down" {
            withFake { _, google, control, http ->
                control.down()
                control.reset()
                http.send("GET", "$google/health").status shouldBe Status.OK
            }
        }

        "the control API is on another port than Google, and Google does not serve it" {
            withFake { fake, google, _, http ->
                (fake.port == fake.controlPort) shouldBe false
                http.send("POST", "$google/control/reset").status shouldBe Status.NOT_FOUND
            }
        }
    })
