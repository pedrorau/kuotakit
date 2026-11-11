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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator

/**
 * Writes what the backend under test is started with, into a volume shared with it: the service account key file
 * (a key pair made now, whose `token_uri` is the fake Google) and the `entitlements.yaml`. Nothing here is committed
 * to the repository, and the key opens nothing: only the fake Google knows it.
 */
internal object Fixtures {
    private const val KEY_BITS = 2048

    fun write(
        directory: Path,
        tokenUri: String,
    ) {
        Files.createDirectories(directory)
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(KEY_BITS) }
                .generateKeyPair()
                .private
        val pem = SigningKey.pem("PRIVATE KEY", key.encoded)
        val json =
            """
            {"type":"service_account","project_id":"conformance","private_key_id":"conformance-key-1",
             "private_key":"${pem.replace("\n", "\\n")}",
             "client_email":"play@conformance.iam.gserviceaccount.com","client_id":"1",
             "token_uri":"$tokenUri"}
            """.trimIndent()
        publish(directory.resolve("google-key.json"), json)
        publish(directory.resolve("entitlements.yaml"), Fixture.ENTITLEMENTS_YAML)
    }

    /** Readable by the backend, which runs as another user in its container. */
    private fun publish(
        file: Path,
        content: String,
    ) {
        Files.writeString(file, content)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
    }
}
