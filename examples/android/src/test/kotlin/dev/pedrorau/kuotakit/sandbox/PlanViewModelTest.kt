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

import dev.pedrorau.kuotakit.sdk.CachedCustomerInfo
import dev.pedrorau.kuotakit.sdk.PlanChange
import dev.pedrorau.kuotakit.sdk.PurchaseOutcome
import dev.pedrorau.kuotakit.sdk.ReplacementMode
import dev.pedrorau.kuotakit.sdk.RestoreOutcome
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlanViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val gateway = FakeGateway()

    private fun ready(model: PlanViewModel) = assertIs<ScreenState.Ready<PlanView>>(model.state.value).data

    @Test
    fun `the plan shows what the backend answered`() {
        gateway.refreshAnswer = { info("premium_monthly") }
        val plan = ready(PlanViewModel(gateway))
        assertEquals(listOf("premium"), plan.entitlements.map { it.id })
        assertTrue(plan.entitlements.single().isActive)
        assertEquals(listOf("premium_monthly"), plan.activeSubscriptions)
    }

    @Test
    fun `the last known answer is never turned into access`() {
        gateway.lastKnown.value = CachedCustomerInfo(info("premium_monthly"), NOW)
        gateway.refreshAnswer = { failing() }
        val model = PlanViewModel(gateway)
        assertIs<ScreenState.Failed>(model.state.value)
        assertEquals(NOW, model.lastKnown.value?.receivedAt)
    }

    @Test
    fun `a failed refresh keeps what was shown and says that it may be out of date`() {
        gateway.refreshAnswer = { info("premium_monthly") }
        val model = PlanViewModel(gateway)
        val before = ready(model)
        assertEquals(false, before.stale)
        gateway.refreshAnswer = { failing() }
        model.refresh()
        assertEquals(before.copy(stale = true), ready(model))
        gateway.refreshAnswer = { info("premium_monthly") }
        model.refresh()
        assertEquals(before, ready(model))
    }

    @Test
    fun `a change of plan that the backend confirms is shown at once, and a cancelled or pending one says so`() {
        gateway.refreshAnswer = { info("premium_monthly") }
        val model = PlanViewModel(gateway)
        val option = SwitchOption(ANNUAL, MONTHLY, ReplacementMode.DEFERRED)
        gateway.purchaseAnswer = { PurchaseOutcome.Success(info("premium_annual")) }
        model.switchPlan(option)
        assertEquals(listOf("premium_annual"), ready(model).activeSubscriptions)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("confirmed"),
        )
        gateway.purchaseAnswer = { PurchaseOutcome.Cancelled }
        model.switchPlan(option)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("Cancelled"),
        )
        gateway.purchaseAnswer = { PurchaseOutcome.Pending }
        model.switchPlan(option)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("nothing is granted"),
        )
        assertEquals(listOf("premium_annual"), ready(model).activeSubscriptions)
    }

    @Test
    fun `a purchase made in the Offerings screen shows in the Plan without asking again`() {
        val model = PlanViewModel(gateway)
        assertEquals(emptyList(), ready(model).activeSubscriptions)
        gateway.customerInfo.value = info("premium_monthly")
        assertEquals(listOf("premium_monthly"), ready(model).activeSubscriptions)
    }

    @Test
    fun `calls do not overlap, a restore at the foreground is skipped while another call runs`() {
        val release = CompletableDeferred<RestoreOutcome>()
        gateway.restoreAnswer = { release.await() }
        val model = PlanViewModel(gateway)
        model.restore()
        model.onForeground()
        model.restore()
        assertEquals(1, gateway.restoreCalls)
        release.complete(RestoreOutcome(info("premium_monthly"), 1, 0, 0))
        model.onForeground()
        assertEquals(2, gateway.restoreCalls)
    }

    @Test
    fun `something that is not a failure of the SDK does not end the app and grants nothing`() {
        gateway.refreshAnswer = { error("a library broke") }
        val model = PlanViewModel(gateway)
        val failed = assertIs<ScreenState.Failed>(model.state.value)
        assertTrue(failed.message.contains("unexpected"))
        gateway.purchaseAnswer = { throw IllegalArgumentException("a library broke") }
        model.switchPlan(SwitchOption(ANNUAL, MONTHLY, ReplacementMode.DEFERRED))
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("Nothing was granted"),
        )
        assertEquals(false, model.busy.value)
    }

    @Test
    fun `a user with a plan can switch to the other one now or when the plan ends`() {
        gateway.refreshAnswer = { info("premium_monthly") }
        val options = ready(PlanViewModel(gateway)).switches
        assertEquals(
            listOf(
                SwitchOption(ANNUAL, MONTHLY, ReplacementMode.CHARGE_PRORATED_PRICE),
                SwitchOption(ANNUAL, MONTHLY, ReplacementMode.DEFERRED),
            ),
            options,
        )
    }

    @Test
    fun `no switch is offered when the plan is not active, nothing is owned or the offerings cannot be read`() {
        gateway.refreshAnswer = { info("premium_monthly", active = false) }
        assertEquals(emptyList(), ready(PlanViewModel(gateway)).switches)
        gateway.refreshAnswer = { info(null) }
        assertEquals(emptyList(), ready(PlanViewModel(gateway)).switches)
        gateway.refreshAnswer = { info("premium_monthly") }
        gateway.offeringsAnswer = { failing() }
        assertEquals(emptyList(), ready(PlanViewModel(gateway)).switches)
    }

    @Test
    fun `a switch asks for the change with the package that is replaced and the mode`() {
        gateway.refreshAnswer = { info("premium_monthly") }
        gateway.purchaseAnswer = { PurchaseOutcome.Pending }
        val model = PlanViewModel(gateway)
        model.switchPlan(SwitchOption(ANNUAL, MONTHLY, ReplacementMode.DEFERRED))
        val (target, change) = gateway.purchases.single()
        assertEquals(ANNUAL, target)
        assertEquals(PlanChange(MONTHLY, ReplacementMode.DEFERRED), change)
    }

    @Test
    fun `a restore shows the counts and what the backend answered`() {
        gateway.restoreAnswer = { RestoreOutcome(info("premium_annual"), submitted = 2, refused = 1, unattributed = 3) }
        val model = PlanViewModel(gateway)
        model.restore()
        assertEquals(listOf("premium_annual"), ready(model).activeSubscriptions)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("2 sent, 1 refused, 3 not attributable"),
        )
    }

    @Test
    fun `a failed restore keeps the plan and reports it`() {
        gateway.refreshAnswer = { info("premium_monthly") }
        val model = PlanViewModel(gateway)
        val before = model.state.value
        gateway.restoreAnswer = { failing() }
        model.restore()
        assertEquals(before, model.state.value)
        assertTrue(
            model.notice.value
                .orEmpty()
                .contains("cannot be reached"),
        )
        assertEquals(false, model.busy.value)
    }

    @Test
    fun `coming to the foreground restores quietly, and a failure there changes nothing`() {
        gateway.refreshAnswer = { info("premium_monthly") }
        val model = PlanViewModel(gateway)
        gateway.restoreAnswer = { RestoreOutcome(info("premium_annual"), 1, 0, 0) }
        model.onForeground()
        assertEquals(listOf("premium_annual"), ready(model).activeSubscriptions)
        val after = model.state.value
        gateway.restoreAnswer = { failing() }
        model.onForeground()
        assertEquals(after, model.state.value)
        assertEquals(null, model.notice.value)
    }
}
