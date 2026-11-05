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

package dev.pedrorau.kuotakit.backend.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.pedrorau.kuotakit.backend.config.DatabaseConfig
import dev.pedrorau.kuotakit.backend.domain.ProcessedEventRepository
import dev.pedrorau.kuotakit.backend.domain.PurchaseRepository
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The connection to Postgres: a pool, the schema migrations and the repositories. Everything that touches the
 * database is created here, so the rest of the backend only sees the interfaces of the `domain` package.
 */
class PostgresStorage private constructor(
    private val pool: HikariDataSource,
) : AutoCloseable {
    private val database = Database.connect(pool)
    val purchases: PurchaseRepository = PostgresPurchaseRepository(database)
    val events: ProcessedEventRepository = PostgresProcessedEventRepository(database)

    override fun close() = pool.close()

    companion object {
        private const val MAX_POOL_SIZE = 10

        /** Connects, brings the schema up to date with Flyway and returns the storage. Fails if Postgres is down. */
        fun open(config: DatabaseConfig): PostgresStorage {
            val pool =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = config.url
                        username = config.user
                        password = config.password
                        maximumPoolSize = MAX_POOL_SIZE
                    },
                )
            var ready = false
            try {
                Flyway
                    .configure()
                    .dataSource(pool)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                ready = true
                return PostgresStorage(pool)
            } finally {
                if (!ready) pool.close() // a failed start-up must not leave a pool of connections open
            }
        }
    }
}
