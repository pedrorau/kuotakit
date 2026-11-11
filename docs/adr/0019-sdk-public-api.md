# ADR 0019 — The public API of the SDK (subphase 1a)

**Status:** Accepted · **Date:** 2026-11-12

## Context
ADR 0002 splits the SDK into a Kotlin Multiplatform core and thin adapters, one per store. ADR 0003 says the client never grants access, ADR 0013 that the backend acknowledges purchases, and ADR 0017 that every Google purchase must carry the account id. The public API of the SDK is a contract (rule 4 of AGENTS.md), so its shape is decided here. Plan: `docs/plans/2026-11-sdk-android-1a.md`.

## Decision
- **Two modules:** `sdk/core` (Kotlin Multiplatform: jvm, android, iosArm64, iosSimulatorArm64; `explicitApi()`; public API dump in `sdk/core/api/`) and `sdk/android` (the Play Billing adapter). The API models are the ones of `contracts/`, never copies.
- **The core** offers `KuotaKit.configure(KuotaKitConfig(baseUrl, apiKey, billing, storage))`, `KuotaKit.shared`, `appUserId`, `getOfferings()`, `getCustomerInfo()`, `purchase(pkg)` and `customerInfo`, a `StateFlow` of the last customer info the backend gave. It also defines the two interfaces a platform implements: `BillingAdapter` and `KeyValueStore`.
- **Failures are exceptions of one sealed class**, `KuotaKitException`, one subclass per row of the table "Errors" of `docs/api-spec.md` plus `Network`, `Unexpected`, `NotConfigured` and `InvalidConfiguration`. The code in the error body decides, not only the status; a body that is not the error body of the contract, or a code the SDK does not know, is `Unexpected`. A cancelled purchase and a pending payment are outcomes (`PurchaseOutcome`), not failures.
- **The SDK never computes access.** `purchase` returns exactly the customer info the backend answered, and a failure leaves `customerInfo` as it was.
- **The adapter never acknowledges:** the backend does, after it checked the purchase (ADR 0013). The Android interface to Play Billing has no acknowledge call.
- **The adapter attaches `playObfuscatedAccountId(appUserId)`** to the purchase when it starts it (ADR 0017); the core computes it and passes it in the `PurchaseRequest`.
- **A secret API key is refused:** `configure` throws `InvalidConfiguration` for a key that starts with `sk_` (rule 3: no secrets in an app) and for one that does not start with `pk_`.
- **The anonymous app user id** is `$anon_` and a random UUID in hex (38 characters, 122 random bits), made once and kept in a `KeyValueStore` (on Android, the app's private preferences). One that was stored but is not valid is replaced.
- **HTTP:** Ktor client (OkHttp on the JVM and Android, Darwin on iOS), a bearer API key, timeouts, no automatic retries.
- **Play Billing Library 9.1.0** (release notes of 2026-06-18), with its Kotlin extensions. A product is looked up as a subscription and, if Google does not know it as one, as a one-time product, because the app only knows its id and the backend decides the kind (ADR 0015). The offer is the base plan, else the first one the user is eligible for.

## Consequences
- A change of the public API shows in the dump and in review.
- The iOS adapter (Phase 2) implements the same `BillingAdapter` and reuses the core.
- Known limit of 1a: if the user buys and the backend cannot be reached when the receipt is sent, `purchase` fails and the purchase stays unacknowledged at Google (refunded after three days). It fails closed, but the user can lose the purchase until subphase 1b adds `restore` and the retry.
- A purchase update that Google sends when no `purchase` call is waiting for it (the call was cancelled while the Play screen was open, a pending payment that completes later, the process was killed) is not sent to the backend in 1a; `restore` in 1b recovers it. A user who already owns an unconfirmed purchase gets `InvalidRequest` when buying it again, for the same reason.
- `ITEM_ALREADY_OWNED` is Google Play's own answer: a subscription or a non-consumable product cannot be bought twice, which is right. Subphase 1b turns it into a recovery of the purchase (look up what the user owns and send the receipt) instead of an error.
- The core never holds the `Activity`: the lambda that gives it lives as long as the SDK, so an app must not capture the activity in it (keep a weak reference).
- Not run on a real device or against the real Google: the code of `BillingClientPlayClient` (the part that talks to `BillingClient`) has no unit test, only the adapter and the failure mapping around it do; it is checked on a device in the task of the example app.

## Alternatives considered
- **`HttpURLConnection` or OkHttp directly:** not multiplatform; the core would need one client per platform.
- **A `Result` type for everything:** heavier for app code; exceptions suit suspend functions and bridge easily.
- **Acknowledging in the app (client-only):** would grant before the server agrees (ADR 0003).
- **The plain app user id as the account id:** rejected in ADR 0017.
