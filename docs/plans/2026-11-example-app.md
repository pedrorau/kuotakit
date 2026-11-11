# Plan: the example Android app (the sandbox)

- **Origin:** context (the identity and the open-source stance were decided with the maintainer earlier; the scope below is the agent's proposal of 2026-11-11 and is a **draft** until approved)
- **Approved by the maintainer on:** not yet (Draft)
- **Branch / PR:** `feat/example-app` / not started

## Goal
An open-source Android app, `dev.pedrorau.kuotakit.sandbox`, that uses the SDK the way an app should (configure, offerings, purchase, plan change, restore, customer info, last known customer info) and that anybody can clone, point at their own backend, and run; and that the maintainer publishes on the Play internal testing track, pointing at a public sandbox backend, so that people can try the whole flow with test payment methods. It is also the vehicle for the exit criterion of Phase 1: a test subscription bought on a real device unlocks `premium`, and a cancellation made in Play Console shows up as not renewing.

## Scope
- A new Gradle module `:examples:android` (Android application, Jetpack Compose with Material 3, one activity), package `dev.pedrorau.kuotakit.sandbox`, `minSdk` 23 and `compileSdk` 37 like the SDK. It depends on the project `:sdk:android` (published coordinates when they exist).
- **Screens, all driven by what the backend answers, never by the app's own idea of access:**
  - *Offerings*: the packages of the current offering (package id and product id, which is all the SDK gives today), a Buy button per package. **The price text from Play is not shown**: the SDK has no price API, and adding one is a change of the public API that needs the maintainer's decision (rule 4 of AGENTS.md); if it is wanted, it is a separate plan.
  - *Plan*: what `customerInfo` says (entitlements with `isActive`, product, expiry, auto-renew, period type), refreshed after every action; the button "Switch to annual" (immediate, `CHARGE_PRORATED_PRICE`) and "Switch to monthly at renewal" (`DEFERRED`) shown according to the product owned.
  - *Restore* (also called when the app comes to the foreground, as `docs/sdk-android.md` says) with the counts of `RestoreOutcome`.
  - *Offline*: the `lastKnownCustomerInfo` with the moment it was received, labelled "not proof of access".
  - *Settings*: **backend address and public key typed at run time** (stored in the app's private preferences), prefilled from the build defaults below. A secret key (`sk_`) is refused with the SDK's own error; the app never asks for one. The settings screen shows the app user id **truncated** and never logs it (ADR 0009). **`KuotaKit.configure` is meant to be called once** (a second call leaves the first Play Billing connection open, `docs/sdk-android.md`): when the address or the key change, the app saves them and **restarts its process** so that the SDK is configured again from scratch, and the screen says so.
- **Build defaults, committed:** `examples/android/sandbox.properties` with the sandbox backend address and its public `pk_` key. **No data of the maintainer** (no personal email, no keystore, no service account): only the sandbox's own public values. Until the sandbox backend exists the file holds a documented placeholder, and the app says so on screen instead of failing. Anyone who clones can override both in `local.properties` (git-ignored) or in the settings screen.
- **Release signing in the same format as CallFilter and Pique** (checked on 2026-11-11 in `CallFilter/CallFilter/androidApp/build.gradle.kts` and `Pique/androidApp/build.gradle.kts`, identical): a `keystore.properties` at the repository root with `storeFile` (relative to the root, `examples/android/upload-keystore.jks`), `storePassword`, `keyAlias` and `keyPassword`; the `release` signing config exists **only if that file exists**, so a fresh clone and CI still build debug; the release type has `isMinifyEnabled = true`, `isShrinkResources = true` and a `proguard-rules.pro`. The maintainer's upload key is the one he already uses in both apps (the same `upload-keystore.jks`, alias `upload`; the two copies have the same checksum); the repository holds none of it: `keystore.properties` is added to `.gitignore` (the `*.jks` and `*.keystore` patterns are already there) and a `keystore.properties.example` with empty values is committed. Without the file `assembleRelease` makes an unsigned release and `assembleDebug` works as usual.
- **A small interface of the app over the SDK** (`SubscriptionsGateway`, app-side, no new SDK symbol): the five calls the screens use (`getOfferings`, `purchase`, `restore`, `customerInfo`, `lastKnownCustomerInfo`). `KuotaKit` is a final class with an internal constructor and cannot be faked, so the view models depend on this interface and one class implements it with `KuotaKit.shared`.
- **Explicit UI state:** one sealed `ScreenState` per screen (`Loading`, `Ready(data)`, `Failed(error)`), with the transitions in the view model (AGENTS.md: no implicit state machines).
- **Architecture rules (Konsist):** the example app imports only the public API of the SDK (no `internal`, no `dev.pedrorau.kuotakit.sdk.android` classes beyond `configure`), contains no `sk_` literal, and calls no billing library directly (so it is also an example of the intended boundary).
- **CI:** `examples/**` is added to the `jvm` path filter of `ci.yml`; the job builds the debug variant and runs lint and the unit tests. It does not need a device or Docker.
- **Docs:** `examples/android/README.md` (clone, configure, run, sign, build the bundle), a link from `docs/sdk-android.md` and `README.md`, and `docs/sandbox-play-console.md` (written already) as the guide to everything outside the repository.
- **Validation on a real device** by the maintainer after parts B to H of the guide: the checklist in "Validation" below, written in the session log with what really happened.

## Out of scope
- iOS (Phase 2), React Native, Flutter.
- Deploying the sandbox backend (stage 2 of the guide is a recipe, not code; a deployment task of its own comes later) and making the app public in Play (production or open testing: it needs the extra testing of new personal accounts, `docs/sandbox-play-console.md` part A).
- A real paywall design, localisation, analytics, crash reporting, ads, any account system (identity is out of v0, ADR 0005).
- Publishing the SDK to Maven Central (Phase 4): the example uses the project dependency.
- Automated UI tests on a device or an emulator (the CI has no Google Play there); the logic that can be tested without one is in the view models.

## Acceptance criteria
- [ ] `./gradlew :examples:android:assembleDebug :examples:android:lintDebug :examples:android:testDebugUnitTest` succeed — checked by: the command, no warnings turned off
- [ ] `./gradlew build` (ktlint, detekt, all modules, the ABI dumps) stays green and the dumps of `contracts` and `sdk/core` do not change — checked by: the command and `git diff -- contracts/api sdk/core/api` empty
- [ ] No public symbol of the SDK is added or changed for the app's sake (rule 4 of AGENTS.md); if something is missing, the plan stops and says so — checked by: the review and the empty ABI diff above
- [ ] The app never shows access that the backend did not answer: the screens read only `customerInfo`, the result of `purchase`/`restore` and `lastKnownCustomerInfo` (labelled as not proof) — checked by: view model unit tests with a fake `SubscriptionsGateway`, including a failure (the previous state stays) and a pending purchase (nothing granted)
- [ ] The settings screen refuses a secret key and a non-`https` address that is not a local test address, and never prints the app user id in full — checked by: unit tests of the validation and a review of every log and `Text` call
- [ ] A Konsist rule keeps the example app on the public API only and free of `sk_`; it has a canary that fails on a violating sample — checked by: `./gradlew :architecture-tests:test` and the canary
- [ ] Release signing reads `keystore.properties` (same keys as CallFilter and Pique, ignored by git, with an example file) and the build still works without it; the repository contains no keystore, password, service account, `sk_` key or email of the maintainer (the sandbox's own public address and `pk_` key are allowed, they are meant to be public) — checked by: `git grep` for `jks`, `storePassword`, `sk_`, `service-account` and the maintainer's email, the guard scripts, and review
- [ ] `examples/**` is part of the `jvm` filter of CI and the job runs it — checked by: the first CI run of the pull request (a changed file under `examples/` starts the job)
- [ ] `examples/android/README.md` lets someone who never saw the project run the app against their own backend — checked by: following it on a clean clone (the debug build and the settings screen)
- [ ] Device validation on the maintainer's phone, against the real Google (see "Validation"), with its result written in the session log; whatever differs from ADR 0020 and ADR 0013 is recorded there, not corrected silently — checked by: the log entry
- [ ] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion

## Steps
- [ ] Gradle module `:examples:android` (version catalog entries for Compose, activity and lifecycle; root `settings.gradle.kts` and the sentence in AGENTS.md → Conventions that lists the modules) and `sandbox.properties` with the placeholder.
- [ ] Application class that configures the SDK once from the settings, the settings storage and its validation, the process restart after a change, and the `SubscriptionsGateway`.
- [ ] View models and screens: offerings, plan, restore, offline, settings; the plan-change buttons.
- [ ] Unit tests of the view models and the validation; the Konsist rule and its canary.
- [ ] Release signing from `keystore.properties` as in CallFilter and Pique, `keystore.properties` in `.gitignore`, the example file, and R8 enabled in release (see the risk below); README of the example; links from the docs; the `jvm` filter in CI.
- [ ] Independent review, fixes, a second pass if needed.
- [ ] After the merge, the maintainer follows the guide (parts B to H), the agent builds the bundle, and the device validation is done and logged (this step may be a separate pull request that only edits docs).

## Validation
- Commands: those of the first three criteria.
- Negative checks planned (break the code on purpose; the new tests must fail, then restore it): a view model that grants from the cache; a failed `purchase` that clears the state; the settings validation accepting `sk_`; a Konsist canary with a sample that imports `internal`.
- **Device checklist (maintainer, real Google, test payment methods), to be recorded in the session log:**
  1. Buy `premium_monthly` with *always approves*: the app shows `premium` active; the backend log shows `200`, and the purchase is **not** refunded after 3 minutes (the backend acknowledged it).
  2. Close and open the app, then `restore()`: same state, no duplicate error.
  3. Switch to annual (immediate), then back to monthly at renewal (`DEFERRED`): check what Google's answer looks like against ADR 0020 (the reading of the two items is **unverified** until now) and record it.
  4. Cancel the subscription in the Play Store: the app shows it as not renewing, still active until the end.
  5. Wait for a renewal (5 minutes) and the end after 6 renewals: access ends; with notifications on (guide part I) without opening the app.
  6. *Always declines* and a slow card that approves later: nothing granted until approved.
  7. A refund from Play Console (**Orders**): access ends once the notification arrives (needs part I).
  8. Whether `expiryTime` moves during the grace period (a known unknown of docs/adr/0013): with Play Billing Lab, put the test subscription in grace period and read the backend's answer.

- **Risk to check: R8 on the release build.** The two other apps minify; this one links the SDK (Ktor, kotlinx.serialization, Play Billing). The SDK has no consumer keep rules today. The release bundle is built with minification on, and the first thing checked on the device is that the app starts, lists the offerings and buys; if something is stripped, the keep rules go in the SDK module (`consumer-rules.pro`, a build file, no public symbol) or in the app's `proguard-rules.pro`, and the session log says which.

## State model
The screen states (`Loading`, `Ready`, `Failed`) are explicit sealed types, one per screen, with their transitions written in the view model that owns them. Nothing else in the app has states with transitions: the SDK owns the purchase and the backend owns the access. The settings (address, key) are plain data.

## Decisions taken
- **Jetpack Compose with Material 3, a single activity** (agent): the usual way to write a small Android app today and it keeps the example short; alternative left out: Views/XML, longer and older. The maintainer may choose otherwise.
- **The backend address and the key are typed in the app at run time** (agent): the quick tunnel of the guide changes address every time and a Play upload takes hours, and the same build then works for anyone who runs their own backend. Alternative left out: addresses only in the build (a new bundle for every address).
- **Committed defaults point at the sandbox** (maintainer): so that people can try the app; only public values of the sandbox, nothing of the maintainer.
- **Products and the offering are the ones of `entitlements.example.yaml`** (`premium_monthly`, `premium_annual`; agent): one set of ids across the examples, the conformance suite and the guide; `lifetime_unlock` stays optional.
- **The signing key and its format are the maintainer's existing ones** (maintainer, 2026-11-11): the upload key and the `keystore.properties` format of CallFilter and Pique, to avoid keeping several. Trade-off said once: one upload key for several apps means that if it leaks, all of them need an upload-key reset in Play Console (the app signing key stays with Google); alternative left out: a separate key for the sandbox.
- **The example uses the project dependency and no published artifact** (agent): publishing is Phase 4.

## Status
Draft
- 2026-11-11: written; waiting for the maintainer's approval. The guide `docs/sandbox-play-console.md` was written with it, because the maintainer has to create things in Play Console before the device validation can happen.
