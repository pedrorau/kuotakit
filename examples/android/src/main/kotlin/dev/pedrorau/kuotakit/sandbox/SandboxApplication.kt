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

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.pedrorau.kuotakit.sdk.KuotaKit
import dev.pedrorau.kuotakit.sdk.KuotaKitException
import dev.pedrorau.kuotakit.sdk.android.configure
import java.lang.ref.WeakReference

/** Remembers the activity in the foreground, weakly: Google Play needs one to show the purchase screen. */
private class ForegroundActivity : Application.ActivityLifecycleCallbacks {
    private var resumed: WeakReference<Activity>? = null

    val current: Activity? get() = resumed?.get()

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Configures KuotaKit once, from the saved settings or the build defaults. `KuotaKit.configure` is meant to be called
 * once, so when the settings change the app restarts (see [restartApp]) and comes here again from scratch.
 */
class SandboxApplication : Application() {
    internal lateinit var settings: SettingsStore
        private set

    internal var gateway: SubscriptionsGateway = UnconfiguredGateway
        private set

    /** Why the SDK is not configured, or `null` when it is. */
    internal var configurationProblem: String? = null
        private set

    private val foreground = ForegroundActivity()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        registerActivityLifecycleCallbacks(foreground)
        when (val checked = SettingsValidation.validate(settings.baseUrl, settings.apiKey)) {
            is Validation.Invalid -> configurationProblem = checked.problem
            is Validation.Valid -> configure(checked)
        }
    }

    private fun configure(settings: Validation.Valid) {
        try {
            gateway =
                KuotaKitGateway(KuotaKit.configure(this, settings.baseUrl, settings.apiKey) { foreground.current })
        } catch (e: KuotaKitException.InvalidConfiguration) {
            configurationProblem = e.describe()
        }
    }
}
