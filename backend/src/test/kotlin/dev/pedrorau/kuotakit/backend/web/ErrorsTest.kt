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

class ErrorsTest :
    StringSpec({
        "the app user id never reaches the log" {
            redactedPath("/v1/subscribers/anon_secret_id") shouldBe "/v1/subscribers/{app_user_id}"
            redactedPath("/v1/subscribers/anon_secret_id/receipts") shouldBe "/v1/subscribers/{app_user_id}/receipts"
            redactedPath("/v1/subscribers/anon_secret_id?x=1") shouldBe "/v1/subscribers/{app_user_id}"
        }

        "other paths are logged as they are, without a query string" {
            redactedPath("/v1/offerings") shouldBe "/v1/offerings"
            redactedPath("/v1/offerings?a=b") shouldBe "/v1/offerings"
        }
    })
