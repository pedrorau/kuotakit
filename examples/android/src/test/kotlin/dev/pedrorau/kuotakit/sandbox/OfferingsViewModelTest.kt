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

package dev.pedrorau.kuotakit.sandbox

import dev.pedrorau.kuotakit.contracts.Offering
import dev.pedrorau.kuotakit.contracts.OfferingPackage
import dev.pedrorau.kuotakit.contracts.OfferingsResponse
import dev.pedrorau.kuotakit.sdk.PlanChange
import dev.pedrorau.kuotakit.sdk.PurchaseOutcome
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfferingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val gateway = FakeGateway()

    @Test
    fun `the packages of the current offering are shown`() {
        val model = OfferingsViewModel(gateway)
        assertEquals(ScreenState.Ready(listOf(MONTHLY, ANNUAL)), model.state.value)
    }

    @Test
    fun `when the current offering is not listed the first one is shown`() {
        gateway.offeringsAnswer = { OfferingsResponse("gone", listOf(Offering("other", listOf(ANNUAL)))) }
        assertEquals(ScreenState.Ready(listOf(ANNUAL)), OfferingsViewModel(gateway).state.value)
    }

    @Test
    fun `a failure to load says so with the text of the app`() {
        gateway.offeringsAnswer = { failing() }
        val state = assertIs<ScreenState.Failed>(OfferingsViewModel(gateway).state.value)
        assertTrue(state.message.contains("cannot be reached"))
    }

    @Test
    fun `a confirmed purchase is reported and the package is bought once`() {
        gateway.purchaseAnswer = { PurchaseOutcome.Success(info("premium_monthly")) }
        val model = OfferingsViewModel(gateway)
        model.buy(MONTHLY)
        assertEquals(listOf<Pair<OfferingPackage, PlanChange?>>(MONTHLY to null), gateway.purchases)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("confirmed"),
        )
        assertNull(model.buying.value)
    }

    @Test
    fun `a pending payment says that nothing is granted, and a cancelled one that it was cancelled`() {
        val model = OfferingsViewModel(gateway)
        gateway.purchaseAnswer = { PurchaseOutcome.Pending }
        model.buy(MONTHLY)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("nothing is granted"),
        )
        gateway.purchaseAnswer = { PurchaseOutcome.Cancelled }
        model.buy(MONTHLY)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("Cancelled"),
        )
    }

    @Test
    fun `something that is not a failure of the SDK is shown as a failure that granted nothing`() {
        gateway.purchaseAnswer = { throw IllegalStateException("a library broke") }
        val model = OfferingsViewModel(gateway)
        model.buy(MONTHLY)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("Nothing was granted"),
        )
        assertNull(model.buying.value)
        gateway.offeringsAnswer = { error("a library broke") }
        assertIs<ScreenState.Failed>(OfferingsViewModel(gateway).state.value)
    }

    @Test
    fun `a failed purchase keeps the offerings and reports the failure`() {
        gateway.purchaseAnswer = { failing() }
        val model = OfferingsViewModel(gateway)
        model.buy(ANNUAL)
        assertEquals(ScreenState.Ready(listOf(MONTHLY, ANNUAL)), model.state.value)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("cannot be reached"),
        )
        assertNull(model.buying.value)
    }

    @Test
    fun `while a purchase is waiting for Google Play another one is not started`() {
        val answer = CompletableDeferred<PurchaseOutcome>()
        gateway.purchaseAnswer = { answer.await() }
        val model = OfferingsViewModel(gateway)
        model.buy(MONTHLY)
        model.buy(ANNUAL)
        assertEquals("monthly", model.buying.value)
        assertEquals(1, gateway.purchases.size)
        answer.complete(PurchaseOutcome.Cancelled)
        assertNull(model.buying.value)
    }
}
