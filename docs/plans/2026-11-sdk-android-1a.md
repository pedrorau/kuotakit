# Plan: the SDK for Android, subphase 1a (the basics)

- **Origin:** context (agreed with the maintainer on 2026-11-12: the SDK is built in subphases, 1a the basics and 1b the rest, and Phase 1 does not close until the Android SDK is complete)
- **Approved by the maintainer on:** 2026-11-12
- **Branch / PR:** `feat/sdk-android-1a` / not started

## Goal
An Android app can use KuotaKit: configure it with the address of its backend and its API key, show the offerings, buy one with Google Play, and read what the user is entitled to. The SDK never decides access (ADR 0003): everything it shows comes from the backend. It attaches the account id to every purchase (ADR 0017). The business logic lives once in a Kotlin Multiplatform core that iOS reuses in Phase 2 (ADR 0002).

## Scope
- Two Gradle modules, added to the root build: **`sdk/core`** (Kotlin Multiplatform: jvm, android, iosArm64, iosSimulatorArm64; `explicitApi()`; public API dump like `contracts`) and **`sdk/android`** (an Android library: the Play Billing adapter). Maven coordinates `dev.pedrorau.kuotakit:core` and `:android` (publishing is Phase 4).
- **Public API of the core** (the contract of this task, rule 4 of AGENTS.md):
  - `KuotaKit.configure(KuotaKitConfig(baseUrl, apiKey, billing, storage))` and `KuotaKit.shared`;
  - `appUserId`: random, generated on first use, stored on the device (`$anon_` plus a random UUID in hex, 122 random bits, which fits `APP_USER_ID_PATTERN`);
  - `suspend getOfferings(): OfferingsResponse` and `suspend getCustomerInfo(): CustomerInfo` (the models of `contracts/`, not copies);
  - `suspend purchase(pkg: OfferingPackage): PurchaseOutcome`: asks the billing adapter to buy, sends the receipt to the backend and returns the customer info the backend answers;
  - `customerInfo: StateFlow<CustomerInfo?>`: the last customer info the backend gave (never computed by the SDK);
  - `sealed class KuotaKitException` with one subclass per failure: `Unauthorized`, `InvalidRequest`, `PurchaseInvalid`, `TokenAlreadyLinked`, `RateLimited(retryAfterSeconds)`, `StoreUnavailable`, `Network`, `Unexpected`, plus `NotConfigured`.
  - The two interfaces a platform implements: `BillingAdapter` and `KeyValueStore`.
- **The API client:** Ktor client with `kotlinx.serialization` and the `kuotaKitJson` of `contracts/`, bearer API key, timeouts, a mapping of every error body of the table "Errors" of `docs/api-spec.md` to its exception, and of `429` with `Retry-After`. No automatic retries in 1a: a failed receipt is an error the app sees.
- **The Android adapter:** a `BillingClient` (Play Billing Library 9.1.0, `billing-ktx`, with auto reconnection) behind a small interface so it can be faked; query product details of the package's `play_store` product; `launchBillingFlow` with `setObfuscatedAccountId(playObfuscatedAccountId(appUserId))`; the result of `PurchasesUpdatedListener` becomes a purchase token handed to the core. The adapter never acknowledges: the backend does it (ADR 0013). A user cancel is `PurchaseOutcome.Cancelled`; a pending payment is `PurchaseOutcome.Pending`.
- **Storage on Android:** `SharedPreferences` in the app's private storage for the anonymous id. Documented: the id is a secret of the user (ADR 0009), so an app should keep it out of backups it does not want to restore.
- **Tests:** the core with `kotlin.test` and Ktor's `MockEngine` on jvm (and the iOS simulator target in CI): configuration errors, id generation and persistence, every error mapping, `purchase` order of events (adapter before backend, no backend call if the user cancels, account id passed to the adapter), no access ever derived locally. The Android adapter with a fake of the `BillingClient` interface on the jvm.
- **Architecture rule (Konsist) with canary:** `sdk/core` may not import Play Billing, Android or iOS frameworks (ADR 0002 says this rule arrives with the module).
- **ADR 0019** "The public API of the SDK" with the decisions below; `docs/sdk-android.md` (how to use it); AGENTS.md (the Gradle modules sentence, commands, Current state); CI (`ci.yml`: filters for `sdk/**`, the Android SDK is already installed for `contracts`).

## Out of scope (subphase 1b, or later)
- `restore`, upgrades and downgrades (the replacement flow must carry the account id too, ADR 0017), retrying a receipt that could not be sent after a successful purchase, offline cache of the customer info, a customer-info listener that reacts to purchases made elsewhere, error handling polish, and the closing of the public API of the Android SDK (1b).
- A test of the SDK against the reference backend and the fake Google in Docker (1b: it needs the compose stack).
- The example app and the test on a real device (their own task), the iOS adapter (Phase 2), bridges (Phase 3), publishing to Maven Central (Phase 4).
- Consumables, user identity (logIn/logOut), promotional offers: out of v0 (ADR 0005).

## Agreed for 1b (maintainer, 2026-11-12)
- On `ITEM_ALREADY_OWNED` the SDK looks up the purchases the user already owns and sends that receipt to the backend, as `restore` does, instead of failing: a purchase that was never confirmed is recovered by buying it again or by restoring.
- A thin layer of our own over `BillingClient` (connection, product lookup, launching the flow, the listener) so that the order of the lookups (subscription, then one-time product), the error handling and the waiting for the purchase can be unit tested. Only the call to Google itself stays for a device test.
- Check in the official documentation what Google requires of the Play Billing Library version an app uses, and so what `minSdk` the SDK can offer; it stays at 23, the minimum of Play Billing 9.1.0 (OkHttp 5.5.1 needs 21), until then.
- Consumables (out of v0, ADR 0005) would need a policy of the backend about how soon the same item may be bought again; it belongs to the abuse-protection ideas of the roadmap.

## Known limit of 1a, written down on purpose
If the user buys and the backend cannot be reached when the receipt is sent, `purchase` fails with `StoreUnavailable` or `Network` and the purchase stays unacknowledged at Google (Google refunds it after three days). Nothing grants access, so it fails closed, but the user can lose a purchase until 1b adds the retry and `restore`.

## Acceptance criteria
- [x] The two modules build, are in `./gradlew build` with ktlint and detekt, and the iOS targets of the core compile — checked by: `./gradlew build` and `./gradlew :sdk:core:compileKotlinIosSimulatorArm64`.
- [x] The public API of the core is dumped and checked like `contracts` — checked by: `./gradlew :sdk:core:checkKotlinAbi`, and the dump lists exactly the symbols of the Scope.
- [x] Every error of the table "Errors" and the `429` map to the right exception, and a body that is not the error body maps to `Unexpected` — checked by: a test per row in `sdk/core` (`ErrorMappingTest`, `KuotaKitTest`).
- [x] `purchase` calls the adapter first with the account id of the current app user, and the backend only after a purchase exists; a cancel or a pending payment makes no backend call — checked by: tests with a fake adapter and a MockEngine that records calls.
- [x] The SDK never grants access by itself: `purchase` returns exactly the customer info of the backend, and a backend error leaves `customerInfo` as it was — checked by: tests; and a review that no code computes `is_active`.
- [x] The anonymous id is generated once, persisted, valid for `APP_USER_ID_PATTERN` and different on a clean store — checked by: tests with a fake store, on the jvm.
- [x] The Android adapter hands the hash of the app user id to the Play client and does not acknowledge, and the Play client sets `setObfuscatedAccountId` — checked by: tests with the fake `PlayPurchaseClient` (a negative check that sends an empty account id fails them); a Konsist rule (with canary) that fails if `BillingClientPlayClient.kt` stops calling `setObfuscatedAccountId`; and `grep -rn acknowledge sdk/*/src/main` finding only comments. The call of the real `BillingClient` is checked on a device in the example app task.
- [x] `sdk/core` does not import billing or platform libraries — checked by: the Konsist rule and its canary.
- [x] The SDK works against the reference backend's answers — checked by: the sample answers in the tests are validated against `docs/api/openapi.json` (the same validator the backend tests use).
- [x] ADR 0019 and `docs/sdk-android.md` exist, and AGENTS.md lists the new modules — checked by: `grep -n "^## " docs/adr/0019-*.md docs/sdk-android.md` and a review.
- [x] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion.

## Steps
- [x] The two modules, the Gradle wiring, the version catalog (Ktor client, MockEngine, Play Billing 9.1.0), the ABI dump.
- [x] The core: model of the flow, API client, error mapping, anonymous id, `KuotaKit`.
- [x] The Android adapter and its fake.
- [x] The tests, the negative checks, the Konsist rule.
- [x] ADR 0019, `docs/sdk-android.md`, AGENTS.md, CI.
- [ ] Review, commit, push, pull request (the maintainer merges).

## Validation
- `./gradlew build` (all modules), the iOS simulator compile, `./gradlew :architecture-tests:test`, `./gradlew :sdk:core:checkKotlinAbi`.
- Negative checks (break the code on purpose, the new tests must fail, then restore): the account id left out of the billing request; the backend called before the purchase exists; a `403` mapped to success; `customerInfo` updated on an error.

## State model
- **The result of a purchase** is explicit: `PurchaseOutcome` is a sealed type with `Success(customerInfo)`, `Cancelled` and `Pending`; failures are exceptions. It is defined in one place (the core) and the Android adapter only produces `Purchased(token)`, `Cancelled` or `Pending` of `BillingAdapter`.
- **The configuration of the SDK** is either not configured or configured: `KuotaKit.shared` throws `NotConfigured` before `configure`, and a second `configure` replaces the first one (documented). No flag that has to agree with another field.
- **The anonymous id** has no lifecycle of its own: it is read from the store or created and stored in one step. Nothing else holds state: `customerInfo` is a plain `StateFlow` of the last answer of the backend.

## Decisions taken
- **Ktor client in the core** (agent, to be approved): the backend and the tests already use Ktor, it is multiplatform (OkHttp on Android, Darwin on iOS), and the MockEngine makes the tests fast. Alternative left out: `HttpURLConnection` or OkHttp directly, which are not multiplatform.
- **Failures are exceptions of one sealed class, success is a value** (agent): natural for suspend functions, easy to bridge later. Alternative left out: `Result` or a sealed result for everything, which is heavier for app code.
- **The adapter does not acknowledge** (ADR 0013): one place decides, and it checks the purchase first. Alternative left out: acknowledge in the app, which is what Google calls client-only and would grant before the server agrees.
- **Anonymous id `$anon_` plus a random UUID** (agent): matches what the backend and the docs already show and is unguessable; `$anon_` is 6 characters and 32 hex characters make 38, inside the limit of 128.
- **Play Billing Library 9.1.0** (agent, from the release notes of 2026-06-18): the latest. Its migration guide and the exact API are checked against the official documentation when the adapter is written (store-docs rules), not taken from memory.
- **Subphases** (maintainer, 2026-11-12): 1a the basics, 1b the complete Android SDK; Phase 1 closes only when 1b, the example app and the check against the real Google are done.
- **Identity of the example app** (maintainer, 2026-11-12): `dev.pedrorau.kuotakit.sandbox`, published as open source in test mode; each user configures their own backend and credentials; no data of the maintainer is published. (Its own task.)

## Status
In progress
- 2026-11-12: written, approved by the maintainer ("si procede"); implementation started.
- 2026-11-12: implemented on the branch; every criterion checked locally (`./gradlew build` green, 26 tests in the core and 8 in the Android module, the iOS simulator tests and the ABI check). Five negative checks failed the tests as they should and were reverted: the plain app user id as account id, a refused purchase read as a conflict, the backend asked when the user cancels, the customer info cleared on a failure, and the Android adapter sending an empty account id.
- Differences from the plan: `compileSdk` of the two modules is 37, not 36, because OkHttp 5 (the Ktor engine) requires it; there is one more exception, `InvalidConfiguration` (a secret key or a bad address in `configure`); the real `BillingClient` code has no unit test (said in ADR 0019). Waiting for the independent review and the maintainer's OK to commit.
- 2026-11-12: independent review (a read-only subagent): no path where the SDK reports access without the backend, and findings fixed: the purchase screen is launched on the main thread; a failed product query is an error, not "unknown product"; only a purchase in the state PURCHASED of the product asked for is handed on; the app user id no longer reaches the message or the cause of a network error; only the newest request sets `customerInfo`; the id is saved with `commit`; `Volatile` on the configuration. Stronger tests (each error row maps to its exact exception, a refused purchase keeps the previous customer info, the order of answers, the leak, a timeout), two more negative checks (stale answer overwriting, the engine message kept as cause) and a Konsist check that the Play client keeps calling `setObfuscatedAccountId`. Recorded as limits in ADR 0019 and `docs/sdk-android.md`: an unconfirmed purchase cannot be bought again, a purchase update with no waiting call is not sent (both fixed by `restore` in 1b), the activity lambda must not hold the activity. Not done on purpose: an ABI dump for `sdk/android` (only the core has one), `https` is not enforced. Waiting for the maintainer's OK to commit.

