# KuotaKit SDK for Android

Subphase 1a: configure, offerings, purchase and customer info. `restore`, plan changes, retries and the offline cache come in subphase 1b (`docs/plans/2026-11-sdk-android-1a.md`). The decisions are in [ADR 0019](adr/0019-sdk-public-api.md).

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
}

val info = kit.getCustomerInfo()                         // what the user may access, from the backend
kit.customerInfo.collect { latest -> /* the last answer of the backend, or null */ }
```
`currentActivity` is the activity in the foreground (or `null`): Google needs it to show the purchase screen. The lambda lives as long as the SDK, so keep only a weak reference to the activity in it (for example the one your `ActivityLifecycleCallbacks` last saw resume), never the activity itself. Call `configure` once, at start-up, and use `https` for `baseUrl`: the API key travels in every request.

## What it never does
- It never decides access: `is_active` comes from the backend, and a failure leaves what you had.
- It never acknowledges a purchase: the backend does, after it checked it with Google.
- It refuses a secret key (`sk_...`): `configure` throws `KuotaKitException.InvalidConfiguration`.

## Errors
Every call can throw a `KuotaKitException`: `Unauthorized` (wrong key), `InvalidRequest`, `PurchaseInvalid` (the store does not confirm the purchase), `TokenAlreadyLinked` (it belongs to another user), `RateLimited(retryAfterSeconds)`, `StoreUnavailable` (the store or billing is not available), `Network`, `Unexpected` and `NotConfigured`.

## The app user id
The SDK makes an anonymous id (`$anon_` and 32 hex characters) the first time and keeps it in the app's private preferences. It is the secret of the user (ADR 0009): do not log it. Reinstalling the app creates a new one, so purchases made under the old one are not found (identity is out of v0, ADR 0005). If your app backs up its preferences and you do not want the id restored on another device, leave the file `kuotakit.xml` out of the backup rules.

## Known limits (1a)
If the user buys and your backend cannot be reached when the receipt is sent, `purchase` throws and the purchase stays unacknowledged at Google, which refunds it after three days. Nothing is granted, but the user can lose the purchase until subphase 1b adds `restore` and the retry. For the same reason buying the same product again fails with `InvalidRequest` ("the user already owns this product"), and a purchase update that arrives when no `purchase` call is waiting (the call was cancelled while the Play screen was open, a pending payment that completes later) is not sent to the backend.
