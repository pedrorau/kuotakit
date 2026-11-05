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

package dev.pedrorau.kuotakit.backend.config

import java.nio.file.Path

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
) {
    // The generated toString would print the password the moment someone logs the configuration.
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, password=***)"
}

/** Where Google Play is asked about purchases: the app it serves and the service account that may read them. */
data class GoogleConfig(
    val packageName: String,
    val serviceAccountFile: Path,
)

/** How a Pub/Sub push proves it is ours: the audience set in the subscription and the account that signs the token. */
data class PubSubConfig(
    val audience: String,
    val serviceAccountEmail: String,
)

/**
 * The backend's settings, read once from the environment at start-up (docker-compose passes them from `.env`). It
 * stops with every problem listed at once instead of failing one variable at a time.
 */
data class AppConfig(
    val port: Int,
    val database: DatabaseConfig,
    val appKeys: List<String>,
    val secretKeys: List<String>,
    val entitlementsFile: Path,
    val google: GoogleConfig? = null,
    val pubSub: PubSubConfig? = null,
) {
    // The generated toString would print the password and every API key the moment someone logs the configuration.
    override fun toString(): String =
        "AppConfig(port=$port, database=$database, appKeys=${appKeys.size} keys, secretKeys=${secretKeys.size} keys, " +
            "entitlementsFile=$entitlementsFile, google=$google, pubSub=$pubSub)"

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_ENTITLEMENTS_FILE = "/config/entitlements.yaml"
        const val MIN_KEY_LENGTH = 16
        private const val PLACEHOLDER = "change_me"

        /** What an `Authorization: Bearer` header can carry (RFC 6750): anything else could never authenticate. */
        private val BEARER_TOKEN = Regex("^[A-Za-z0-9._~+/-]+=*$")
        private const val MAX_PORT = 65535

        fun fromEnv(env: Map<String, String>): AppConfig {
            val problems = mutableListOf<String>()

            fun required(name: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: "".also { problems += "$name is required" }

            val port =
                env["PORT"]?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
                        ?: DEFAULT_PORT.also { problems += "PORT must be a number between 1 and $MAX_PORT, not '$raw'" }
                } ?: DEFAULT_PORT
            val database =
                DatabaseConfig(required("DATABASE_URL"), required("DATABASE_USER"), required("DATABASE_PASSWORD"))
            val appKeys = keys(env["KUOTAKIT_APP_KEYS"], "KUOTAKIT_APP_KEYS", "pk_", problems)
            val secretKeys = keys(env["KUOTAKIT_SECRET_KEYS"], "KUOTAKIT_SECRET_KEYS", "sk_", problems)
            if (appKeys.isEmpty() && secretKeys.isEmpty() && problems.none { it.startsWith("KUOTAKIT_") }) {
                problems +=
                    "set KUOTAKIT_APP_KEYS (the key the SDK uses) and, for your own servers, KUOTAKIT_SECRET_KEYS"
            }
            val google = google(env, problems)
            val pubSub = pubSub(env, google, problems)
            if (problems.isNotEmpty()) {
                throw ConfigException("Invalid configuration:\n" + problems.joinToString("\n") { " - $it" })
            }
            return AppConfig(
                port = port,
                database = database,
                appKeys = appKeys,
                secretKeys = secretKeys,
                entitlementsFile =
                    Path.of(env["KUOTAKIT_ENTITLEMENTS_FILE"]?.takeIf { it.isNotBlank() } ?: DEFAULT_ENTITLEMENTS_FILE),
                google = google,
                pubSub = pubSub,
            )
        }

        /** The push proof for the webhook. Both or none, and only with Google Play configured. */
        private fun pubSub(
            env: Map<String, String>,
            google: GoogleConfig?,
            problems: MutableList<String>,
        ): PubSubConfig? {
            val audience = env["KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE"]?.trim().orEmpty()
            val email = env["KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL"]?.trim().orEmpty()
            if (audience.isEmpty() && email.isEmpty()) return null
            if (audience.isEmpty()) {
                problems +=
                    "KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE is required with the service account email"
            }
            if (email.isEmpty()) {
                problems +=
                    "KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL is required with the audience"
            }
            if (email.isNotEmpty() && '@' !in email) {
                problems += "KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL must be an email address"
            }
            if (google == null) {
                problems += "KUOTAKIT_GOOGLE_PUBSUB_* need KUOTAKIT_GOOGLE_PACKAGE_NAME and the service account file"
            }
            return if (audience.isEmpty() || email.isEmpty()) null else PubSubConfig(audience, email)
        }

        /** Both or none: half a Google configuration is a mistake, not "Google is off". */
        private fun google(
            env: Map<String, String>,
            problems: MutableList<String>,
        ): GoogleConfig? {
            val packageName = env["KUOTAKIT_GOOGLE_PACKAGE_NAME"]?.trim().orEmpty()
            val file = env["KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE"]?.trim().orEmpty()
            if (packageName.isEmpty() && file.isEmpty()) return null
            if (packageName.isEmpty()) {
                problems +=
                    "KUOTAKIT_GOOGLE_PACKAGE_NAME is required with the service account file"
            }
            if (file.isEmpty()) problems += "KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE is required with the package name"
            return if (packageName.isEmpty() || file.isEmpty()) null else GoogleConfig(packageName, Path.of(file))
        }

        private fun keys(
            raw: String?,
            name: String,
            prefix: String,
            problems: MutableList<String>,
        ): List<String> {
            val keys =
                raw
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            keys.forEach { key ->
                if (!key.startsWith(prefix)) problems += "$name: every key must start with $prefix"
                if (key.length <
                    MIN_KEY_LENGTH
                ) {
                    problems += "$name: a key must have at least $MIN_KEY_LENGTH characters"
                }
                if (!BEARER_TOKEN.matches(key)) {
                    problems += "$name: a key may only have letters, digits and . _ ~ + / - (and = at the end), " +
                        "because it travels in an Authorization header"
                }
                if (PLACEHOLDER in key) {
                    problems += "$name: replace the placeholder key from .env.example with a long random one"
                }
            }
            return keys
        }
    }
}
