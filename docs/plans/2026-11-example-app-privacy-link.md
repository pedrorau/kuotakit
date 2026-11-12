# Plan: the example app links to its privacy policy and says "not set up yet" kindly

- **Origin:** context (found while preparing the Play Console listing; approved by the maintainer: "si procede" on 2026-11-11)
- **Approved by the maintainer on:** 2026-11-11
- **Branch / PR:** `feat/example-app-privacy-link` / not yet

## Goal
Google Play requires the privacy policy to be linked **inside the app** as well as in the store listing ([Prepare your app for review](https://support.google.com/googleplay/android-developer/answer/9859455), [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)). The example app has no link. Also, an app that is not set up yet shows a technical error ("not a valid URL") in Settings.

## Scope
- A **Privacy policy** button in Settings that opens the URL `privacyPolicyUrl` of `examples/android/sandbox.properties` (default `https://kuotakit.pedrorau.dev/privacy.html`, overridable like the other defaults); hidden when the URL is empty.
- An empty backend address says "Not set up yet: type the address of your backend (https://...) and its public key (pk_...)." instead of the URL error.
- README of the example.

## Out of scope
- A privacy-policy screen inside the app, any change of the SDK or its public API, the deletion mechanism promised in the policy (it belongs to the sandbox deployment).

## Acceptance criteria
- [x] Settings shows a Privacy policy button that opens the configured URL, and none when it is empty — checked by: `BuildConfig.PRIVACY_POLICY_URL` from `sandbox.properties` in the debug build; seen on an emulator
- [x] An empty address gives the "Not set up yet" message and other invalid addresses keep their own — checked by: `SettingsValidationTest`
- [x] `./gradlew build` stays green and the ABI dumps do not change — checked by: the command
- [x] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report

## Steps
- [x] The setting, the BuildConfig field and the button; the message and its test; README.
- [x] Validation, review, PR.

## Validation
- `./gradlew :examples:android:testDebugUnitTest :examples:android:lintDebug build`; negative check: remove the empty-address branch, the new test must fail, then restore it.

## State model
None (a button and a message; the Settings states are unchanged).

## Decisions taken
- The URL lives in `sandbox.properties` (agent): the policy covers the maintainer's sandbox backend; someone running another backend changes it, like the other defaults.

## Status
Done
- 2026-11-11: written and approved; implemented. Checked: tests, ktlint, detekt, lint; a negative check (removing the empty-address branch fails the new test); the Settings screen on an emulator shows the message and the button. Independent review: nothing blocks; its findings were applied: opening the link cannot crash the app on a device with no browser (it shows the address instead) and the message names only the address.
