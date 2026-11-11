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

package dev.pedrorau.kuotakit.sdk.android

import android.app.Activity
import android.content.Context
import dev.pedrorau.kuotakit.sdk.KuotaKit
import dev.pedrorau.kuotakit.sdk.KuotaKitConfig

/**
 * Sets KuotaKit up for an Android app with Google Play billing and the app's private preferences. [apiKey] is the
 * public key of the app (`pk_...`); [activity] gives the activity in the foreground, or `null`.
 */
public fun KuotaKit.Companion.configure(
    context: Context,
    baseUrl: String,
    apiKey: String,
    activity: () -> Activity?,
): KuotaKit =
    configure(KuotaKitConfig(baseUrl, apiKey, PlayBilling(context, activity), SharedPreferencesStore(context)))
