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

package dev.pedrorau.kuotakit.contracts

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** The exact JSON of docs/api-spec.md, in both directions: the models are the wire contract. */
class ModelsJsonTest {
    private val json = kuotaKitJson

    private inline fun <reified T> roundTrip(
        value: T,
        expected: String,
    ) {
        assertEquals(expected, json.encodeToString(value), "encoding")
        assertEquals(value, json.decodeFromString<T>(expected), "decoding")
    }

    @Test
    fun customerInfoMatchesTheSpecExample() {
        val premium =
            Entitlement(
                isActive = true,
                productId = "premium_monthly",
                store = Store.PLAY_STORE,
                periodType = PeriodType.NORMAL,
                expiresAt = Instant.parse("2026-12-01T12:00:00Z"),
                willRenew = true,
                billingIssueDetectedAt = null,
            )
        roundTrip(
            CustomerInfo(
                appUserId = "\$anon_6f1c",
                requestDate = Instant.parse("2026-11-01T12:00:00Z"),
                entitlements = mapOf("premium" to premium),
                activeSubscriptions = listOf("premium_monthly"),
                nonSubscriptionPurchases = emptyList(),
            ),
            """{"app_user_id":"${'$'}anon_6f1c","request_date":"2026-11-01T12:00:00Z","entitlements":{"premium":""" +
                """{"is_active":true,"product_id":"premium_monthly","store":"play_store","period_type":"normal",""" +
                """"expires_at":"2026-12-01T12:00:00Z","will_renew":true,"billing_issue_detected_at":null}},""" +
                """"active_subscriptions":["premium_monthly"],"non_subscription_purchases":[]}""",
        )
    }

    @Test
    fun nonSubscriptionPurchasesUseSnakeCase() {
        roundTrip(
            NonSubscriptionPurchase("lifetime", Store.APP_STORE, Instant.parse("2026-11-01T12:00:00Z")),
            """{"product_id":"lifetime","store":"app_store","purchased_at":"2026-11-01T12:00:00Z"}""",
        )
    }

    @Test
    fun anEntitlementThatNeverExpiresKeepsTheExplicitNull() {
        val lifetime = Entitlement(true, "pro", Store.APP_STORE, PeriodType.TRIAL, null, false, null)
        roundTrip(
            lifetime,
            """{"is_active":true,"product_id":"pro","store":"app_store","period_type":"trial",""" +
                """"expires_at":null,"will_renew":false,"billing_issue_detected_at":null}""",
        )
    }

    @Test
    fun offeringsMatchTheSpecExample() {
        roundTrip(
            OfferingsResponse(
                current = "default",
                offerings =
                    listOf(
                        Offering(
                            "default",
                            listOf(
                                OfferingPackage(
                                    "monthly",
                                    StoreProducts("premium_monthly", "com.acme.premium.monthly"),
                                ),
                                OfferingPackage("annual", StoreProducts("premium_annual", "com.acme.premium.annual")),
                            ),
                        ),
                    ),
            ),
            """{"current":"default","offerings":[{"id":"default","packages":[""" +
                """{"id":"monthly","products":""" +
                """{"play_store":"premium_monthly","app_store":"com.acme.premium.monthly"}},""" +
                """{"id":"annual","products":""" +
                """{"play_store":"premium_annual","app_store":"com.acme.premium.annual"}}]}]}""",
        )
    }

    @Test
    fun aPackageNotSoldInOneStoreHasAnExplicitNull() {
        roundTrip(StoreProducts("only_android", null), """{"play_store":"only_android","app_store":null}""")
    }

    @Test
    fun receiptRequestsAreToldApartByTheStoreField() {
        roundTrip<ReceiptRequest>(
            PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "token-123"),
            """{"store":"play_store","package_name":"com.acme.app","product_id":"premium_monthly",""" +
                """"purchase_token":"token-123"}""",
        )
        roundTrip<ReceiptRequest>(
            AppStoreReceiptRequest("com.acme.app", "eyJhbGciOi"),
            """{"store":"app_store","bundle_id":"com.acme.app","signed_transaction":"eyJhbGciOi"}""",
        )
    }

    @Test
    fun aReceiptWithAnUnknownStoreIsRejected() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<ReceiptRequest>("""{"store":"windows_store","product_id":"x"}""")
        }
    }

    @Test
    fun errorsHaveACodeAndAMessage() {
        roundTrip(
            ErrorResponse(ErrorBody(ErrorCode.TOKEN_ALREADY_LINKED, "The purchase token belongs to another user.")),
            """{"error":{"code":"token_already_linked","message":"The purchase token belongs to another user."}}""",
        )
    }

    @Test
    fun everyErrorCodeHasItsOwnWireName() {
        val names = ErrorCode.entries.map { json.encodeToString(it).trim('"') }
        assertEquals(
            listOf(
                "invalid_payload",
                "purchase_invalid",
                "token_already_linked",
                "store_unavailable",
                "unauthorized",
                "internal_error",
                "unknown",
            ),
            names,
        )
    }

    @Test
    fun aRequiredNullableFieldCannotBeOmitted() {
        // `null` must be sent explicitly, so a client can tell "no date" from a field a backend forgot.
        assertFailsWith<SerializationException> {
            json.decodeFromString<StoreProducts>("""{"play_store":"x"}""")
        }
    }

    @Test
    fun clientsCanIgnoreFieldsAddedByNewerBackends() {
        val lenient = Json { ignoreUnknownKeys = true }
        val parsed =
            lenient.decodeFromString<ErrorResponse>(
                """{"error":{"code":"unauthorized","message":"m","hint":"new field"}}""",
            )
        assertEquals(ErrorCode.UNAUTHORIZED, parsed.error.code)
    }

    @Test
    fun anEnumValueAnOlderClientDoesNotKnowDecodesAsUnknown() {
        val entitlement =
            json.decodeFromString<Entitlement>(
                """{"is_active":true,"product_id":"p","store":"galaxy_store","period_type":"prepaid",""" +
                    """"expires_at":null,"will_renew":false,"billing_issue_detected_at":null}""",
            )
        assertEquals(Store.UNKNOWN, entitlement.store)
        assertEquals(PeriodType.UNKNOWN, entitlement.periodType)
        val error = json.decodeFromString<ErrorResponse>("""{"error":{"code":"rate_limited","message":"slow down"}}""")
        assertEquals(ErrorCode.UNKNOWN, error.error.code)
    }

    @Test
    fun anUnknownValueSurvivesBeingSerializedAgain() {
        // The SDK caches the last customer info by serializing it: UNKNOWN must read back as UNKNOWN.
        val unknown = Entitlement(true, "p", Store.UNKNOWN, PeriodType.UNKNOWN, null, false, null)
        assertEquals(unknown, json.decodeFromString<Entitlement>(json.encodeToString(unknown)))
    }

    @Test
    fun aValueThatIsNotEvenAStringIsStillAnError() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<Entitlement>(
                """{"is_active":true,"product_id":"p","store":"play_store","period_type":{"x":1},""" +
                    """"expires_at":null,"will_renew":false,"billing_issue_detected_at":null}""",
            )
        }
    }

    @Test
    fun theKnownWireNamesStillDecodeToTheirMembers() {
        assertEquals(Store.APP_STORE, json.decodeFromString<Store>("\"app_store\""))
        assertEquals(PeriodType.INTRO, json.decodeFromString<PeriodType>("\"intro\""))
        assertEquals(ErrorCode.INTERNAL_ERROR, json.decodeFromString<ErrorCode>("\"internal_error\""))
    }

    @Test
    fun theSharedJsonConfigurationKeepsNullsAndIgnoresUnknownKeys() {
        assertEquals("""{"play_store":"a","app_store":null}""", kuotaKitJson.encodeToString(StoreProducts("a", null)))
        assertEquals(
            StoreProducts("a", "b"),
            kuotaKitJson.decodeFromString("""{"play_store":"a","app_store":"b","future":1}"""),
        )
    }
}
