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

package dev.pedrorau.kuotakit.conformance

/** The two levels of ADR 0007: the contract (no Google needed) and the behaviour against the stores (a fake Google). */
internal enum class Level(
    val label: String,
) {
    ONE("Level 1"),
    TWO("Level 2"),
}

/** What a scenario can use: the backend under test and, for Level 2, the fake Google it talks to. */
internal class Context(
    val backend: Backend,
    val google: FakeGoogleControl?,
)

/** One check of the manifest. [referenceOnly] scenarios describe temporary behaviour of the reference backend. */
internal class Scenario(
    val id: String,
    val level: Level,
    val referenceOnly: Boolean = false,
    val run: Context.() -> Unit,
)

/** A scenario found something the backend does wrong. An exception, so that the runner reports it and goes on. */
internal class ScenarioFailure(
    message: String,
) : RuntimeException(message)

internal fun check(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw ScenarioFailure(message())
}

internal fun <T> expectEqual(
    expected: T,
    actual: T,
    what: String,
) = check(expected == actual) { "$what: expected <$expected> but was <$actual>" }

/** The scenarios of the suite, by ID. The manifest (`conformance/manifest.txt`) and this list must match. */
internal fun allScenarios(): List<Scenario> =
    (level1AccessScenarios() + level1ShapeScenarios() + level2Scenarios()).sortedBy { it.id.replace("R-", "Z-") }

internal fun level2Scenarios(): List<Scenario> =
    level2Subscriptions1() +
        level2Subscriptions2() +
        level2OneTime() +
        level2Ownership() +
        level2Failures() +
        level2WebhookAuth() +
        level2WebhookEvents() +
        level2Deferred()
