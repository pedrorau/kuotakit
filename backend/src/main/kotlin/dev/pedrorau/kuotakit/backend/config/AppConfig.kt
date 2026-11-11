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

import java.net.URI
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
    /** Another address for the Play Developer API, for the conformance suite only (docs/adr/0018). */
    val apiBaseUrl: String? = null,
)

/**
 * How often a client may call the API per minute (docs/adr/0016): every authenticated call by client address, the calls
 * that reach Google by client address and by app user id. [trustForwardedFor] reads the client address from the last
 * `X-Forwarded-For` value, which is only right behind a reverse proxy that sets it.
 */
data class RateLimitConfig(
    val perMinute: Int = 300,
    val receiptsPerMinute: Int = 30,
    val receiptsPerUserPerMinute: Int = 10,
    val trustForwardedFor: Boolean = false,
)

/** How a Pub/Sub push proves it is ours: the audience set in the subscription and the account that signs the token. */
data class PubSubConfig(
    val audience: String,
    val serviceAccountEmail: String,
    /** Another address to fetch the signing keys of the push tokens from, for the conformance suite only. */
    val certsUrl: String? = null,
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
    val rateLimit: RateLimitConfig = RateLimitConfig(),
) {
    // The generated toString would print the password and every API key the moment someone logs the configuration.
    override fun toString(): String =
        "AppConfig(port=$port, database=$database, appKeys=${appKeys.size} keys, secretKeys=${secretKeys.size} keys, " +
            "entitlementsFile=$entitlementsFile, google=$google, pubSub=$pubSub, rateLimit=$rateLimit)"

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_ENTITLEMENTS_FILE = "/config/entitlements.yaml"
        const val MIN_KEY_LENGTH = 16
        private const val ALLOW_CUSTOM_URLS = "KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS"
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
            checkCustomUrlLock(env, problems)
            val google = google(env, problems)
            val pubSub = pubSub(env, google, problems)
            val rateLimit = rateLimit(env, problems)
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
                rateLimit = rateLimit,
            )
        }

        /**
         * An address that replaces one of Google's, which only the conformance suite needs (docs/adr/0018). Using one
         * takes `KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS=true`, so that a mistake in production cannot point the backend at
         * a fake server; it must be an http or https address with a host.
         */
        private fun customUrl(
            env: Map<String, String>,
            name: String,
            problems: MutableList<String>,
        ): String? {
            val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (env[ALLOW_CUSTOM_URLS]?.trim() != "true") {
                problems += "$name is for testing only: it needs $ALLOW_CUSTOM_URLS=true, so that a mistake cannot " +
                    "point the backend at a fake Google"
            }
            val uri = runCatching { URI(raw) }.getOrNull()
            // `authority` and not `host`: the host of an address such as `http://fake_google:9000/` (a compose service
            // name with an underscore) is null for `URI`, although Java and Google's clients connect to it.
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.authority.isNullOrEmpty()) {
                problems += "$name must be an http or https address with a host, not '$raw'"
            }
            return raw
        }

        /** The lock is exactly `true` or `false` (surrounding spaces are ignored), whether or not an address is set. */
        private fun checkCustomUrlLock(
            env: Map<String, String>,
            problems: MutableList<String>,
        ) {
            val lock = env[ALLOW_CUSTOM_URLS]?.trim()?.takeIf { it.isNotEmpty() }
            if (lock != null && lock != "true" && lock != "false") {
                problems += "$ALLOW_CUSTOM_URLS must be true or false, not '$lock'"
            }
        }

        private fun rateLimit(
            env: Map<String, String>,
            problems: MutableList<String>,
        ): RateLimitConfig {
            val defaults = RateLimitConfig()

            fun perMinute(
                name: String,
                default: Int,
            ): Int {
                val raw = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return default
                return raw.toIntOrNull()?.takeIf { it >= 1 }
                    ?: default.also { problems += "$name must be a whole number of at least 1, not '$raw'" }
            }

            val trustRaw = env["KUOTAKIT_TRUST_FORWARDED_FOR"]?.trim()?.takeIf { it.isNotEmpty() }
            val trust =
                when (trustRaw) {
                    null, "false" -> {
                        false
                    }

                    "true" -> {
                        true
                    }

                    else -> {
                        false.also {
                            problems +=
                                "KUOTAKIT_TRUST_FORWARDED_FOR must be true or false, not '$trustRaw'"
                        }
                    }
                }
            return RateLimitConfig(
                perMinute = perMinute("KUOTAKIT_RATE_LIMIT_PER_MINUTE", defaults.perMinute),
                receiptsPerMinute = perMinute("KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_MINUTE", defaults.receiptsPerMinute),
                receiptsPerUserPerMinute =
                    perMinute("KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_USER_PER_MINUTE", defaults.receiptsPerUserPerMinute),
                trustForwardedFor = trust,
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
            val certsUrl = customUrl(env, "KUOTAKIT_GOOGLE_CERTS_URL", problems)
            if (audience.isEmpty() && email.isEmpty()) {
                if (certsUrl != null) problems += "KUOTAKIT_GOOGLE_CERTS_URL needs the Pub/Sub settings of the webhook"
                return null
            }
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
            return if (audience.isEmpty() || email.isEmpty()) null else PubSubConfig(audience, email, certsUrl)
        }

        /** Both or none: half a Google configuration is a mistake, not "Google is off". */
        private fun google(
            env: Map<String, String>,
            problems: MutableList<String>,
        ): GoogleConfig? {
            val packageName = env["KUOTAKIT_GOOGLE_PACKAGE_NAME"]?.trim().orEmpty()
            val file = env["KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE"]?.trim().orEmpty()
            val baseUrl = customUrl(env, "KUOTAKIT_GOOGLE_API_BASE_URL", problems)
            if (packageName.isEmpty() && file.isEmpty()) {
                if (baseUrl != null) problems += "KUOTAKIT_GOOGLE_API_BASE_URL needs the Google Play settings"
                return null
            }
            if (packageName.isEmpty()) {
                problems +=
                    "KUOTAKIT_GOOGLE_PACKAGE_NAME is required with the service account file"
            }
            if (file.isEmpty()) problems += "KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE is required with the package name"
            return if (packageName.isEmpty() ||
                file.isEmpty()
            ) {
                null
            } else {
                GoogleConfig(packageName, Path.of(file), baseUrl)
            }
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
