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
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class FixturesTest :
    StringSpec({
        "the fixtures are a service account key file that points at the fake Google and the entitlements" {
            val dir = Files.createTempDirectory("fixtures")
            Fixtures.write(dir, "http://fake-google:9000/token")

            val account = Json.parseToJsonElement(Files.readString(dir.resolve("google-key.json"))).jsonObject
            account.getValue("type").jsonPrimitive.content shouldBe "service_account"
            account.getValue("token_uri").jsonPrimitive.content shouldBe "http://fake-google:9000/token"
            val pem = account.getValue("private_key").jsonPrimitive.content
            pem shouldContain "BEGIN PRIVATE KEY"
            val der = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)).algorithm shouldBe "RSA"

            Files.readString(dir.resolve("entitlements.yaml")) shouldBe Fixture.ENTITLEMENTS_YAML
        }

        "the files can be read by the user the backend runs as, which is another one" {
            val dir = Files.createTempDirectory("fixtures")
            Fixtures.write(dir, "http://x/token")
            listOf("google-key.json", "entitlements.yaml").forEach {
                Files.getPosixFilePermissions(dir.resolve(it)) shouldBe
                    setOf(
                        PermissionOf.OWNER_READ,
                        PermissionOf.OWNER_WRITE,
                        PermissionOf.GROUP_READ,
                        PermissionOf.OTHERS_READ,
                    )
            }
        }

        "every run makes another key" {
            val a = Files.createTempDirectory("fixtures")
            val b = Files.createTempDirectory("fixtures")
            Fixtures.write(a, "http://x/token")
            Fixtures.write(b, "http://x/token")
            (Files.readString(a.resolve("google-key.json")) == Files.readString(b.resolve("google-key.json"))) shouldBe
                false
        }
    })

private typealias PermissionOf = PosixFilePermission
