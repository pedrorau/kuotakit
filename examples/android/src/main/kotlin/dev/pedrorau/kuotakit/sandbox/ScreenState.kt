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

/** What a screen shows, one of three; the transitions are made by the view model that owns it. */
internal sealed interface ScreenState<out T> {
    /** Waiting for the first answer. */
    data object Loading : ScreenState<Nothing>

    /** The answer came; [data] is all the screen shows. */
    data class Ready<T>(
        val data: T,
    ) : ScreenState<T>

    /** The call failed with [message]; nothing was granted and the screen keeps nothing from it. */
    data class Failed(
        val message: String,
    ) : ScreenState<Nothing>
}
