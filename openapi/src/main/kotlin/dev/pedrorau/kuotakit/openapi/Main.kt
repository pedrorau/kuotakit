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

package dev.pedrorau.kuotakit.openapi

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "Usage: <output file> | --check <committed file>"

/**
 * `<file>` writes the generated document there; `--check <file>` fails when the committed file differs from what
 * the models and endpoints produce today. Run through Gradle: `generateOpenApi`, `updateOpenApi`, `checkOpenApi`.
 */
fun main(args: Array<String>) {
    val generated = OpenApiDocument.render()
    when {
        args.size == 1 && !args[0].startsWith("--") -> {
            File(args[0]).also { it.parentFile?.mkdirs() }.writeText(generated)
            println("OpenAPI document written to ${args[0]}")
        }

        args.size == 2 && args[0] == "--check" -> {
            val committed = File(args[1]).takeIf { it.isFile }?.readText()
            if (committed != generated) {
                System.err.println(
                    "${args[1]} is out of date with the contracts models and the endpoint table.\n" +
                        "If the change is intentional, run ./gradlew :openapi:updateOpenApi and commit the result; " +
                        "an API change must be called out in the plan (AGENTS.md rule 4).",
                )
                exitProcess(1)
            }
            println("${args[1]} is up to date.")
        }

        else -> {
            System.err.println(USAGE)
            exitProcess(2)
        }
    }
}
