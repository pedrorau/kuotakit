# KuotaKit SDK for Android

The Android SDK of v0: configure, offerings, purchase, plan changes, customer info, `restore` and the last known customer info (plans: `docs/plans/2026-11-sdk-android-1a.md`, `1b-i`, `1b-ii`; the decisions are in [ADR 0019](adr/0019-sdk-public-api.md)).

An example app that uses all of this is in [`examples/android`](../examples/android/README.md).

## Add it
```kotlin
dependencies {
    implementation("dev.pedrorau.kuotakit:android:<version>")   // brings the core
}
```
Not published yet (Phase 4); until then build it from this repository: `./gradlew :sdk:android:assembleRelease`.

## Use it
```kotlin
import dev.pedrorau.kuotakit.sdk.KuotaKit
import dev.pedrorau.kuotakit.sdk.PurchaseOutcome
import dev.pedrorau.kuotakit.sdk.android.configure

// In your Application or first Activity. The key is the PUBLIC key of the app (pk_...), never a secret key.
KuotaKit.configure(context, baseUrl = "https://kuotakit.example.com", apiKey = "pk_...") { currentActivity }

val kit = KuotaKit.shared
val offerings = kit.getOfferings()                       // what the backend sells
val pkg = offerings.offerings.first().packages.first()

when (val outcome = kit.purchase(pkg)) {
    is PurchaseOutcome.Success -> show(outcome.customerInfo.entitlements["premium"]?.isActive == true)
    PurchaseOutcome.Cancelled -> Unit                    // the user closed the purchase screen
    PurchaseOutcome.Pending -> showPendingPayment()      // not paid yet: nothing is granted
    is PurchaseOutcome.AlreadyOwned -> show(outcome.customerInfo) // Google says the user owns it; the SDK restored it
}

val info = kit.getCustomerInfo()                         // what the user may access, from the backend
val restored = kit.restore()                             // find the purchases of this user in Google Play and send them
kit.customerInfo.collect { latest -> /* the last answer of the backend, or null */ }
```
`currentActivity` is the activity in the foreground (or `null`): Google needs it to show the purchase screen. The lambda lives as long as the SDK, so keep only a weak reference to the activity in it (for example the one your `ActivityLifecycleCallbacks` last saw resume), never the activity itself. Call `configure` once, at start-up (a second call stops the background sync of the first, but its Play Billing connection stays open), and use `https` for `baseUrl`: the API key travels in every request.

## Change plan
```kotlin
// Upgrade now: the old plan is replaced at once.
kit.purchase(annual, PlanChange(replacing = monthly, mode = ReplacementMode.CHARGE_PRORATED_PRICE))
// Downgrade: the user keeps what they paid for and the new plan starts when the old one ends.
kit.purchase(monthly, PlanChange(replacing = annual, mode = ReplacementMode.DEFERRED))
```
You name the package to replace; the SDK finds that subscription among what the user owns (never a purchase token of yours). If the user owns no such subscription the call fails with `InvalidRequest` and nothing is shown. Google's recommendation: `CHARGE_PRORATED_PRICE` to upgrade, `DEFERRED` to downgrade. Subscriptions with add-ons (`KEEP_EXISTING`) are not supported.

## Offline: the last known customer info
`kit.lastKnownCustomerInfo` is the last answer of the backend, kept on the device, with the moment it was received (`CachedCustomerInfo`). It needs no network and survives restarts, and it is replaced by every answer. It is **not proof of access**: the SDK never turns it into entitlements (`customerInfo` and every result of a purchase are only what the backend answered), and the file can be edited on a rooted device. Use it to show what the user had while there is no network, and decide how long your app trusts it.

## What it never does
- It never decides access: `is_active` comes from the backend, and a failure leaves what you had.
- It never acknowledges a purchase: the backend does, after it checked it with Google.
- It refuses a secret key (`sk_...`): `configure` throws `KuotaKitException.InvalidConfiguration`.

## Test it with no device
The repository has an end-to-end test that runs the SDK against the real reference backend and a fake Google, with a fake Play that reports to the fake Google what Play would (using the account id your SDK attached). With Docker running:
```bash
./gradlew :backend:installDist :conformance:installDist
docker compose -f docker-compose.conformance.yml -f docker-compose.conformance.reference.yml -f docker-compose.sdk-e2e.yml \
  up -d --build backend fake-google postgres
./gradlew :sdk:e2e:e2eTest
docker compose -f docker-compose.conformance.yml -f docker-compose.conformance.reference.yml -f docker-compose.sdk-e2e.yml down -v
```
It is not part of `./gradlew build` (it needs Docker); CI runs it in the job `sdk-e2e`.

## Errors
Every call can throw a `KuotaKitException`: `Unauthorized` (wrong key), `InvalidRequest`, `PurchaseInvalid` (the store does not confirm the purchase), `TokenAlreadyLinked` (it belongs to another user), `RateLimited(retryAfterSeconds)`, `StoreUnavailable` (the store or billing is not available), `Network`, `Unexpected` and `NotConfigured`.

## The app user id
The SDK makes an anonymous id (`$anon_` and 32 hex characters) the first time and keeps it in the app's private preferences. It is the secret of the user (ADR 0009): do not log it. Reinstalling the app creates a new one, so purchases made under the old one are not found (identity is out of v0, ADR 0005). If your app backs up its preferences and you do not want the id restored on another device, leave the file `kuotakit.xml` out of the backup rules.

## Restore and recovery
Google keeps a purchase until it is confirmed or refunded, so a purchase whose receipt could not be sent (no network, the backend down) is not lost: the SDK finds it again and sends it.
- **At start-up** (`configure`) and **whenever Google reports a purchase that no `purchase` call is waiting for** (a pending payment that is approved later), the SDK syncs in the background. A failure there changes nothing you can see; the next trigger tries again.
- **Call `restore()` when your app comes to the foreground** (for example from `onResume` of your main activity). Google recommends syncing when the connection is made; the SDK has no timers of its own. It returns `RestoreOutcome(customerInfo, submitted, refused, unattributed)`: what the backend says, how many purchases it accepted, how many it refused, and how many were found but could not be attributed to this app user (see below). A failure to reach the backend throws, and nothing is lost in Google Play; the backend limits the receipts of one app user (10 a minute by default), so a purchase it already answered is not sent again in the same run of the app, and a restore that hits the limit throws `RateLimited` (in the background it is kept for the next trigger).
- **Buying what the user already owns** does not fail: `purchase` restores it and returns `PurchaseOutcome.AlreadyOwned(customerInfo)` if the backend accepted that product; if the backend refused it you get `PurchaseInvalid`.
- **What cannot be recovered:** only purchases that carry this app user's account id are sent (the backend refuses the others, ADR 0017). A purchase made outside the app (a promo code), on another device with another app user id, or before the app was reinstalled has another account id or none. In that case buying it again throws `InvalidRequest` saying so. Identity (logIn) is out of v0.
