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

import dev.pedrorau.kuotakit.backend.config.RateLimitConfig
import dev.pedrorau.kuotakit.contracts.APP_USER_ID_PATTERN
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes

/** Every authenticated call, by the address of the client. */
internal val API_LIMIT = RateLimitName("api")

/** The calls that make the backend ask Google, by the address of the client. */
internal val RECEIPTS_IP_LIMIT = RateLimitName("receipts-ip")

/** The same calls, by app user id. */
internal val RECEIPTS_USER_LIMIT = RateLimitName("receipts-user")

private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val IPV6_CHARACTERS = Regex("""^[0-9A-Fa-f:.]{2,45}$""")
private val APP_USER_ID = Regex(APP_USER_ID_PATTERN)

/** The bucket of every app user id that is not valid: they all fail at the same door, so they share one budget. */
private const val INVALID_ID = "invalid-id"

/**
 * The address a call counts against. Behind a reverse proxy the connection always comes from the proxy, so every user
 * would share one budget; with [trustForwardedFor] the last value of the last `X-Forwarded-For` line (what the proxy
 * appended, not what the client wrote) is used instead. Without the setting the header is ignored, because anyone
 * could forge it to dodge the limit. A value that is not an IP address is not used, and an IPv6 address counts as its
 * /64 network: one host has billions of addresses in it.
 */
internal fun clientAddress(
    remoteAddress: String,
    forwardedFor: List<String>,
    trustForwardedFor: Boolean,
): String {
    val forwarded =
        if (trustForwardedFor) {
            forwardedFor
                .lastOrNull()
                ?.split(',')
                ?.lastOrNull()
                ?.trim()
                ?.let(::parseAddress)
        } else {
            null
        }
    return (forwarded ?: parseAddress(remoteAddress))?.let(::network) ?: remoteAddress
}

/**
 * An IP address, or `null` for anything else. Only literals are parsed (a text with a colon is never looked up, and
 * an IPv4 text is checked first), so this never asks a name server.
 */
private fun parseAddress(text: String): InetAddress? =
    if (IPV4.matches(text) || (':' in text && IPV6_CHARACTERS.matches(text))) {
        runCatching { InetAddress.getByName(text) }.getOrNull()
    } else {
        null
    }

private fun network(address: InetAddress): String =
    if (address is Inet6Address) {
        "v6:" + address.address.take(IPV6_NETWORK_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it) }
    } else {
        address.hostAddress
    }

private const val IPV6_NETWORK_BYTES = 8

private fun ApplicationCall.forwardedFor(): List<String> = request.headers.getAll("X-Forwarded-For").orEmpty()

private fun ApplicationCall.clientAddress(trustForwardedFor: Boolean): String =
    clientAddress(request.origin.remoteAddress, forwardedFor(), trustForwardedFor)

/**
 * What a call to the API counts against: the client address and whether the call carries a key the backend accepts.
 * Calls with a wrong key have a bucket of their own, so a flood of them can never use up the budget of the calls that
 * carry a good one, even when the backend sees every client at the same address (docs/adr/0016).
 */
private data class CallerKey(
    val address: String,
    val knownKey: Boolean,
)

/** A wrong key costs this many calls of its own bucket (never more than the whole bucket). */
private const val WRONG_KEY_WEIGHT = 10

private fun ApplicationCall.carriesKnownKey(apiKeys: ApiKeys): Boolean {
    val header = runCatching { request.parseAuthorizationHeader() }.getOrNull()
    val token = (header as? HttpAuthHeader.Single)?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }?.blob
    return token != null && apiKeys.authenticate(token) != null
}

/**
 * Tells, once, that the backend seems to be behind a reverse proxy while it was not told to trust the forwarded
 * address: every client then shares one budget, which is easy to miss (docs/adr/0016).
 */
internal class ProxyHint {
    private val told = AtomicBoolean(false)

    fun shouldTell(
        forwardedFor: List<String>,
        trustForwardedFor: Boolean,
    ): Boolean = !trustForwardedFor && forwardedFor.isNotEmpty() && told.compareAndSet(false, true)
}

/**
 * Limits how often a client may call the API (docs/adr/0016). The API key is public and shared by every user of the
 * app, so a limit by key would let one client use up everybody's share: calls are counted by client address and, for
 * receipts, by app user id. The counters live in this process, which fits a single instance behind Docker; the
 * library forgets a key as soon as its bucket refills, so rotating ids cannot grow the memory.
 */
internal fun Application.installRateLimits(
    config: RateLimitConfig,
    apiKeys: ApiKeys,
) {
    val proxyHint = ProxyHint()
    install(RateLimit) {
        register(API_LIMIT) {
            rateLimiter(limit = config.perMinute, refillPeriod = 1.minutes)
            requestKey { call ->
                if (proxyHint.shouldTell(call.forwardedFor(), config.trustForwardedFor)) {
                    call.application.log.warn(
                        "A request came with X-Forwarded-For but KUOTAKIT_TRUST_FORWARDED_FOR is not true: if this " +
                            "backend is behind a reverse proxy, every client shares one request budget. Set it to " +
                            "true (only behind a proxy that sets the header).",
                    )
                }
                CallerKey(call.clientAddress(config.trustForwardedFor), call.carriesKnownKey(apiKeys))
            }
            requestWeight {
                _,
                key,
                ->
                if ((key as CallerKey).knownKey) 1 else minOf(WRONG_KEY_WEIGHT, config.perMinute)
            }
        }
        register(RECEIPTS_IP_LIMIT) {
            rateLimiter(limit = config.receiptsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.clientAddress(config.trustForwardedFor) }
        }
        register(RECEIPTS_USER_LIMIT) {
            rateLimiter(limit = config.receiptsPerUserPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.parameters["app_user_id"]?.takeIf { APP_USER_ID.matches(it) } ?: INVALID_ID }
        }
    }
}
