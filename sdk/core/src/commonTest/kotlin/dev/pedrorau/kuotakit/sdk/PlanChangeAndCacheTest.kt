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

package dev.pedrorau.kuotakit.sdk

import dev.pedrorau.kuotakit.contracts.CustomerInfo
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.PlayStoreReceiptRequest
import dev.pedrorau.kuotakit.contracts.Store
import dev.pedrorau.kuotakit.contracts.StoreProducts
import dev.pedrorau.kuotakit.contracts.kuotaKitJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private val MONTHLY = OfferingPackage("monthly", StoreProducts("premium_monthly", null))
private val ANNUAL = OfferingPackage("annual", StoreProducts("premium_annual", null))
private val ONLY_APPLE = OfferingPackage("apple", StoreProducts(null, "com.acme.only.apple"))

private class RecordingBilling : BillingAdapter {
    val requests = mutableListOf<PurchaseRequest>()
    override val store: Store = Store.PLAY_STORE
    override val purchaseUpdates: Flow<OwnedPurchase> = emptyFlow()

    override suspend fun purchase(request: PurchaseRequest): BillingResult {
        requests += request
        return BillingResult.Purchased(
            PlayStoreReceiptRequest("com.acme.app", request.productId, "tok-${requests.size}"),
        )
    }

    override suspend fun ownedPurchases(): List<OwnedPurchase> = emptyList()
}

private class FixedAt(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

private fun answering(
    status: HttpStatusCode,
    body: () -> String,
) = MockEngine {
    respond(body(), status, Headers.build { append(HttpHeaders.ContentType, "application/json") })
}

private fun kit(
    store: KeyValueStore,
    engine: MockEngine,
    billing: BillingAdapter = RecordingBilling(),
    clock: Clock = Clock.System,
) = KuotaKit.create(KuotaKitConfig("https://b.example.com", "pk_abc", billing, store), engine, clock)

/** The answer of the backend for [id]: the backend echoes the app user id it was asked about. */
private fun premiumFor(id: String) = Samples.CUSTOMER_PREMIUM.replace("\"anon\"", "\"$id\"")

private fun emptyFor(id: String) = Samples.CUSTOMER_EMPTY.replace("\"anon\"", "\"$id\"")

class PlanChangeTest {
    @Test
    fun aPlanChangeTellsTheStoreWhichProductIsReplacedAndHow() =
        runTest {
            val billing = RecordingBilling()
            val store = MemoryStore()
            val kit = kit(store, answering(HttpStatusCode.OK) { Samples.CUSTOMER_PREMIUM }, billing)

            ReplacementMode.entries.forEach { mode -> kit.purchase(ANNUAL, PlanChange(MONTHLY, mode)) }

            assertEquals(
                ReplacementMode.entries.map { ProductReplacement("premium_monthly", it) },
                billing.requests.map { it.replacing },
            )
            billing.requests.forEach {
                assertEquals("premium_annual", it.productId)
                assertEquals(kit.appUserId, it.appUserId)
            }
        }

    @Test
    fun aFirstPurchaseReplacesNothing() =
        runTest {
            val billing = RecordingBilling()
            kit(MemoryStore(), answering(HttpStatusCode.OK) { Samples.CUSTOMER_PREMIUM }, billing).purchase(MONTHLY)
            assertNull(billing.requests.single().replacing)
        }

    @Test
    fun theFiveModesOfGoogleAreOffered() {
        assertEquals(
            listOf(
                "CHARGE_PRORATED_PRICE",
                "WITH_TIME_PRORATION",
                "CHARGE_FULL_PRICE",
                "WITHOUT_PRORATION",
                "DEFERRED",
            ),
            ReplacementMode.entries.map { it.name },
        )
    }

    @Test
    fun aChangeBetweenPackagesNotSoldHereOrToItselfIsRefusedBeforeAskingTheStore() =
        runTest {
            val billing = RecordingBilling()
            val kit = kit(MemoryStore(), answering(HttpStatusCode.OK) { Samples.CUSTOMER_PREMIUM }, billing)
            assertFailsWith<KuotaKitException.InvalidRequest> {
                kit.purchase(ANNUAL, PlanChange(ONLY_APPLE, ReplacementMode.DEFERRED))
            }
            assertFailsWith<KuotaKitException.InvalidRequest> {
                kit.purchase(ANNUAL, PlanChange(ANNUAL, ReplacementMode.CHARGE_FULL_PRICE))
            }
            assertEquals(emptyList(), billing.requests)
        }

    @Test
    fun theRequestNeverShowsTheAppUserIdOrTheAccountIdInItsText() {
        val request =
            PurchaseRequest(
                "premium_annual",
                "\$anon_thesecret",
                "hashoftheaccount",
                ProductReplacement("premium_monthly", ReplacementMode.DEFERRED),
            )
        val text = request.toString()
        assertTrue("thesecret" !in text && "hashoftheaccount" !in text, text)
        assertTrue("premium_annual" in text && "DEFERRED" in text, text)
    }

    @Test
    fun theValueTypesAreComparedByTheirContent() {
        val a = PurchaseRequest("p", "u", "a", ProductReplacement("q", ReplacementMode.DEFERRED))
        val b = PurchaseRequest("p", "u", "a", ProductReplacement("q", ReplacementMode.DEFERRED))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != PurchaseRequest("p", "u", "a"))
    }
}

class OfflineCacheTest {
    private val received = Instant.parse("2026-11-12T10:00:00Z")

    @Test
    fun theLastAnswerIsKeptWithItsMomentAndIsAvailableInAnotherRunWithNoNetwork() =
        runTest {
            val store = MemoryStore()
            val first =
                kit(store, answering(HttpStatusCode.OK) { premiumFor("placeholder") }, clock = FixedAt(received))
            val answer = premiumFor(first.appUserId)
            val running = kit(store, answering(HttpStatusCode.OK) { answer }, clock = FixedAt(received))
            running.getCustomerInfo()

            val offline = kit(store, MockEngine { throw IllegalStateException("no network") })
            val cached = assertNotNull(offline.lastKnownCustomerInfo.value)
            assertEquals(received, cached.receivedAt)
            assertEquals(
                true,
                cached.customerInfo.entitlements
                    .getValue("premium")
                    .isActive,
            )
            assertFailsWith<KuotaKitException.Network> { offline.getCustomerInfo() }
        }

    @Test
    fun theCacheIsNeverTheAnswerOfTheBackend() =
        runTest {
            val store = MemoryStore()
            val online = kit(store, answering(HttpStatusCode.OK) { "{}" })
            val answer = premiumFor(online.appUserId)
            kit(store, answering(HttpStatusCode.OK) { answer }).getCustomerInfo()

            val offline = kit(store, MockEngine { throw IllegalStateException("no network") })
            assertNotNull(offline.lastKnownCustomerInfo.value)
            assertNull(offline.customerInfo.value)
            assertFailsWith<KuotaKitException.Network> { offline.getCustomerInfo() }
            assertFailsWith<KuotaKitException.Network> { offline.purchase(MONTHLY) }
            assertNull(offline.customerInfo.value)
        }

    @Test
    fun everyAnswerReplacesTheCacheAndAFailureDoesNot() =
        runTest {
            val store = MemoryStore()
            val clock = FixedAt(received)
            var answer = ""
            var status = HttpStatusCode.OK
            val kit =
                kit(
                    store,
                    MockEngine {
                        respond(answer, status, Headers.build { append(HttpHeaders.ContentType, "application/json") })
                    },
                    clock = clock,
                )
            answer = emptyFor(kit.appUserId)
            kit.getCustomerInfo()
            assertEquals(
                0,
                kit.lastKnownCustomerInfo.value
                    ?.customerInfo
                    ?.entitlements
                    ?.size,
            )

            clock.now = received.plus(kotlin.time.Duration.parse("2h"))
            answer = premiumFor(kit.appUserId)
            kit.getCustomerInfo()
            assertEquals(
                1,
                kit.lastKnownCustomerInfo.value
                    ?.customerInfo
                    ?.entitlements
                    ?.size,
            )
            assertEquals(clock.now, kit.lastKnownCustomerInfo.value?.receivedAt)

            status = HttpStatusCode.BadGateway
            answer = Samples.error("store_unavailable")
            assertFailsWith<KuotaKitException.StoreUnavailable> { kit.getCustomerInfo() }
            assertEquals(
                1,
                kit.lastKnownCustomerInfo.value
                    ?.customerInfo
                    ?.entitlements
                    ?.size,
            )
        }

    @Test
    fun aCacheOfAnotherAppUserIsDroppedNotShown() =
        runTest {
            val store = MemoryStore()
            val first = kit(store, answering(HttpStatusCode.OK) { "{}" })
            kit(store, answering(HttpStatusCode.OK) { premiumFor("\$anon_somebody_else") }).getCustomerInfo()
            assertNull(kit(store, MockEngine { throw IllegalStateException("no network") }).lastKnownCustomerInfo.value)
            assertTrue(first.appUserId.isNotBlank())
        }

    @Test
    fun garbageInTheCacheIsIgnoredAndNeverAnError() =
        runTest {
            listOf(
                "",
                "not json",
                "{}",
                "[]",
                """{"received_at":"yesterday","customer_info":{}}""",
                """{"received_at":"2026-11-12T10:00:00Z"}""",
                "null",
            ).forEach { garbage ->
                val store = MemoryStore()
                store.put("kuotakit.customer_info", garbage)
                val kit = kit(store, answering(HttpStatusCode.OK) { Samples.CUSTOMER_EMPTY })
                assertNull(kit.lastKnownCustomerInfo.value, "cache '$garbage'")
            }
        }

    @Test
    fun anAnswerAboutAnotherAppUserIsNeverTheCacheOfThisOne() =
        runTest {
            val store = MemoryStore()
            val kit = kit(store, answering(HttpStatusCode.OK) { premiumFor("\$anon_somebody_else") })
            kit.getCustomerInfo()
            assertNull(kit.lastKnownCustomerInfo.value)
            assertNull(store.get("kuotakit.customer_info"))
        }

    @Test
    fun aCacheOfAnotherAppUserIsEmptiedFromTheStoreWhenFound() =
        runTest {
            val store = MemoryStore()
            val first = kit(store, answering(HttpStatusCode.OK) { "{}" })
            val saved = premiumFor(first.appUserId)
            kit(store, answering(HttpStatusCode.OK) { saved }).getCustomerInfo()
            val written = store.get("kuotakit.customer_info")
            assertTrue(written != null && written.isNotEmpty())

            // The same device, another app user: its id is not the one in the cache.
            store.put("kuotakit.app_user_id", "\$anon_a_new_user_on_the_same_device")
            assertNull(kit(store, MockEngine { throw IllegalStateException("no network") }).lastKnownCustomerInfo.value)
            assertEquals("", store.get("kuotakit.customer_info"))
        }

    @Test
    fun aStorageThatFailsToWriteNeverTurnsAnAnswerOfTheBackendIntoAnError() =
        runTest {
            val inner = MemoryStore()
            val failing =
                object : KeyValueStore {
                    override fun get(key: String) = inner.get(key)

                    override fun put(
                        key: String,
                        value: String,
                    ) {
                        check(key != "kuotakit.customer_info") { "disk full" }
                        inner.put(key, value)
                    }
                }
            val probe = kit(inner, answering(HttpStatusCode.OK) { "{}" })
            val answer = premiumFor(probe.appUserId)
            val kit = kit(failing, answering(HttpStatusCode.OK) { answer })

            val info = kit.getCustomerInfo()

            assertEquals(info, kit.customerInfo.value)
            assertEquals(true, info.entitlements.getValue("premium").isActive)
        }

    @Test
    fun noValueTypeShowsASecretInItsText() =
        runTest {
            val kit = kit(MemoryStore(), answering(HttpStatusCode.OK) { "{}" })
            val info = Json.decodeCustomer(premiumFor(kit.appUserId))
            val receipt = PlayStoreReceiptRequest("com.acme.app", "premium_monthly", "thetokensecret")
            val texts =
                listOf(
                    BillingResult.Purchased(receipt).toString(),
                    OwnedPurchase(receipt, OwnedState.PURCHASED, "theaccountid").toString(),
                    PurchaseOutcome.Success(info).toString(),
                    PurchaseOutcome.AlreadyOwned(info).toString(),
                    RestoreOutcome(info, 1, 0, 0).toString(),
                    PurchaseRequest("p", kit.appUserId, "theaccountid").toString(),
                )
            texts.forEach { text ->
                assertTrue("thetokensecret" !in text && "theaccountid" !in text && kit.appUserId !in text, text)
            }
        }

    @Test
    fun aBaseAddressWithAQueryAFragmentOrSpacesIsRefusedBecausePathsAreAddedToIt() {
        val refused = listOf("https://b.example.com/?a=b", "https://b.example.com/#top", "https://b.example.com/a b")
        refused.forEach { base ->
            assertFailsWith<KuotaKitException.InvalidConfiguration>(base) {
                KuotaKit.create(
                    KuotaKitConfig(
                        base,
                        "pk_abc",
                        RecordingBilling(),
                        MemoryStore(),
                    ),
                    answering(HttpStatusCode.OK) {
                        "{}"
                    },
                )
            }
        }
    }
}

private object Json {
    fun decodeCustomer(text: String): CustomerInfo = kuotaKitJson.decodeFromString(CustomerInfo.serializer(), text)
}
