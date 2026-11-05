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

package dev.pedrorau.kuotakit.backend.testing

import dev.pedrorau.kuotakit.backend.config.DatabaseConfig
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer

/** One real Postgres for the whole test run, started the first time a test needs it. */
object TestPostgres {
    private val container: PostgreSQLContainer by lazy {
        check(DockerClientFactory.instance().isDockerAvailable) {
            "Docker is required for the storage tests. On macOS: colima start --network-address, and set " +
                "DOCKER_HOST, TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE and TESTCONTAINERS_HOST_OVERRIDE (see AGENTS.md)."
        }
        PostgreSQLContainer("postgres:18").also { it.start() }
    }

    fun newDatabase(): DatabaseConfig {
        val name = "test_${System.nanoTime()}"
        container.createConnection("").use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        val url = container.jdbcUrl.replaceAfterLast('/', name)
        return DatabaseConfig(url, container.username, container.password)
    }
}
