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

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val DEFAULT_WAIT_SECONDS = 120L
private const val POLL_MILLIS = 1000L

private fun setting(
    name: String,
    default: String,
) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

private fun waitFor(
    url: String,
    seconds: Long,
): Boolean {
    val http = Http()
    val end = System.currentTimeMillis() + seconds * POLL_MILLIS
    while (System.currentTimeMillis() < end) {
        if (runCatching { http.send("GET", url).status == Status.OK }.getOrDefault(false)) return true
        Thread.sleep(POLL_MILLIS)
    }
    return false
}

private fun fakeGoogle(): Nothing {
    val publicUrl = setting("CONFORMANCE_FAKE_GOOGLE_URL", "http://fake-google:${Fixture.FAKE_GOOGLE_PORT}")
    Fixtures.write(Path.of(setting("CONFORMANCE_FIXTURES_DIR", "/fixtures")), "$publicUrl/token")
    FakeGoogle(Fixture.FAKE_GOOGLE_PORT, Fixture.FAKE_GOOGLE_CONTROL_PORT, SigningKey.generate()).use {
        println("Fake Google listening on port ${it.port}; fixtures written.")
        CountDownLatch(1).await()
    }
    exitProcess(0)
}

private fun suite(selfCheck: Boolean): Nothing {
    val missing = Manifest.missing()
    if (missing.isNotEmpty()) {
        println("The manifest lists scenarios that this suite does not have: $missing")
        exitProcess(2)
    }
    val backendUrl = setting("CONFORMANCE_BACKEND_URL", "http://backend:8080")
    val googleUrl =
        setting("CONFORMANCE_FAKE_GOOGLE_CONTROL_URL", "http://fake-google:${Fixture.FAKE_GOOGLE_CONTROL_PORT}")
    val level = setting("CONFORMANCE_LEVEL", "all")
    require(level in setOf("1", "all")) { "CONFORMANCE_LEVEL must be 1 or all, not '$level'" }
    val selection = Selection(level2 = level == "all", reference = setting("CONFORMANCE_REFERENCE", "false") == "true")
    val seconds = setting("CONFORMANCE_WAIT_SECONDS", DEFAULT_WAIT_SECONDS.toString()).toLong()
    if (!waitFor("$backendUrl/health", seconds)) {
        println("The backend under test did not answer GET $backendUrl/health within $seconds seconds.")
        exitProcess(2)
    }
    val contract = Contract.bundled()
    val google = if (waitFor("$googleUrl/health", 1)) FakeGoogleControl(googleUrl) else null
    if (selfCheck) exitProcess(if (SelfCheck(backendUrl, contract, google, selection).run()) 0 else 1)
    println("Conformance suite against $backendUrl (${if (selection.level2) "all levels" else "Level 1 only"})")
    val report = Runner({ Backend(backendUrl, contract) }, google).run(selection)
    print(report.text())
    exitProcess(report.verdict.exitCode)
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        null -> {
            suite(selfCheck = false)
        }

        "self-check" -> {
            suite(selfCheck = true)
        }

        "fake-google" -> {
            fakeGoogle()
        }

        "ping" -> {
            exitProcess(if (waitFor(args.getOrElse(1) { error("ping needs a URL") }, 1)) 0 else 1)
        }

        else -> {
            println("Usage: conformance [self-check | fake-google | ping <url>]")
            exitProcess(2)
        }
    }
}
