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

package dev.pedrorau.kuotakit.architecture

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.StringSpec

class ArchitectureTest :
    StringSpec({
        "@Serializable types live only in contracts" {
            ArchitectureRules.serializableTypesLiveOnlyInContracts(Konsist.scopeFromProduction())
        }

        "the common code of contracts imports only Kotlin and kotlinx" {
            ArchitectureRules.contractsCommonCodeIsPlatformIndependent(Konsist.scopeFromProduction())
        }

        "only the storage package of the backend uses the persistence libraries" {
            ArchitectureRules.persistenceLibrariesAreUsedOnlyByTheStoragePackage(Konsist.scopeFromProduction())
        }

        "the domain of the backend knows neither Ktor nor the web and storage packages" {
            ArchitectureRules.domainDoesNotDependOnTheWebOrStorage(Konsist.scopeFromProduction())
        }
    })
