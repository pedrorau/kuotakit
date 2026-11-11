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

package dev.pedrorau.kuotakit.backend.google

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.auth.oauth2.GooglePublicKeysManager
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import dev.pedrorau.kuotakit.backend.domain.PushTokenVerifier
import dev.pedrorau.kuotakit.backend.domain.StoreUnavailableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Checks the OpenID Connect token that a Pub/Sub push subscription sends in `Authorization: Bearer`
 * (https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions): signed by Google, issued by
 * `https://accounts.google.com`, not expired, for our [audience], from the service account [serviceAccountEmail]
 * and with a verified email. Anything else is `false`.
 */
class GooglePushTokenVerifier internal constructor(
    private val verifier: GoogleIdTokenVerifier,
    private val serviceAccountEmail: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PushTokenVerifier {
    override suspend fun verify(bearerToken: String): Boolean {
        // Reading the token is separate from checking it: a token that cannot even be read is simply not valid,
        // while a failure to fetch Google's keys (below) is ours or Google's and must make Pub/Sub retry.
        val token = parse(bearerToken) ?: return false
        return try {
            withContext(ioDispatcher) { verifier.verify(token) } &&
                token.payload.emailVerified == true &&
                token.payload.email == serviceAccountEmail
        } catch (e: IOException) {
            throw StoreUnavailableException("Google's signing keys could not be fetched.", e)
        } catch (ignored: GeneralSecurityException) {
            // A signature that cannot be checked (an unusable key or algorithm) is not a valid signature.
            false
        }
    }

    /** The address the signing keys of the push tokens are fetched from. */
    internal val certsUrl: String get() = verifier.publicKeysManager.publicCertsEncodedUrl

    private fun parse(bearerToken: String): GoogleIdToken? =
        try {
            GoogleIdToken.parse(verifier.publicKeysManager.jsonFactory, bearerToken)
        } catch (ignored: IOException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }

    companion object {
        fun create(
            audience: String,
            serviceAccountEmail: String,
            certsUrl: String? = null,
        ): GooglePushTokenVerifier {
            val keys =
                GooglePublicKeysManager
                    .Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .apply {
                        // Only the conformance suite uses another address (docs/adr/0018).
                        certsUrl?.let { setPublicCertsEncodedUrl(it) }
                    }.build()
            return GooglePushTokenVerifier(
                GoogleIdTokenVerifier.Builder(keys).setAudience(listOf(audience)).build(),
                serviceAccountEmail,
            )
        }
    }
}
