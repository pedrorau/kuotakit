# Plan: the SDK for Android, subphase 1c (three fixes found in the first run against the real Google)

- **Origin:** context (found on 2026-11-12 by the maintainer's test on a real phone with the example app, license tester and the real Google Play; the backend and Google were read-only inspected to confirm each point)
- **Approved by the maintainer on:** 2026-11-12 ("si lo apruebo"; he will also connect the phone by USB so that the run is repeated with a device log)
- **Branch / PR:** `feat/sdk-android-1c` / not yet

## Goal
Three behaviours of the Android SDK that the fake Google and the end-to-end test could not show, and that the first real purchases did:
1. **A deferred plan change never returns.** `purchase(pkg, PlanChange(replacing, DEFERRED))` completed in Google and reached the backend (through the background sync), but the call stayed waiting for ever and the app kept its buttons disabled until it was closed.
2. **After a renewal the backend still says "not active" and Restore does nothing.** Without Google's notifications the backend only learns of a renewal when the app sends the receipt again; the SDK sends a purchase once per run of the app, so `restore()` answers "0 sent" and the plan stays expired until the app restarts.
3. **An unexpected exception from Play Billing would escape the SDK** (the SDK has no `catch` of its own; open since the review of the example app).

## What was observed (2026-11-12, real Google, all read-only checks)
- Monthly test subscription bought: Google `ACTIVE`, `ACKNOWLEDGED`, account id equal to the SHA-256 of the app user id; the backend stored it correctly.
- Renewal (every 5 minutes in test): Google renewed; the backend kept the old end date, so the Plan went to "not active" while Google said `ACTIVE`; Restore said "0 sent"; closing and reopening the app fixed it (a new run sends the purchase again).
- Deferred change to annual: Google answered (through the Play Developer API) with **two line items** (`premium_annual` with no end date, `premium_monthly` with its end and `deferredItemReplacement = premium_annual`), the new purchase `ACTIVE`, `ACKNOWLEDGED` and with a `linkedPurchaseToken`, and the old one `EXPIRED`. The backend read it as ADR 0020 says; after the monthly ended and the app restarted, it showed `premium_annual` active. **So the backend side of ADR 0020 is verified against the real Google.**
- The call of the app stayed waiting. The code that waits (`PlayBilling.answers`) accepts only an update whose purchase lists the product asked for (`premium_annual`). The reading that explains what was seen, **not yet confirmed on the device** (no device log was taken): for a deferred change Google reports the new purchase with the **replaced** product (`premium_monthly`), so the call never recognises its own answer; the purchase reached the backend by the background sync instead, which is why the screen did update.

## Scope
- **Fix 1 (`sdk/android`, `PlayBilling`):** a call that replaces a subscription recognises its answer when the purchase lists the product asked for **or** the replaced one, provided it is a purchase of this app user (account id) and its token is **not the token of the replaced purchase** (which the adapter already found). The receipt sent still names the product asked for (the backend accepts either, scenario L2-38). The forwarder of background updates skips the same updates while a call waits for them. The waiting state becomes one small value (product asked for, product replaced, token replaced) instead of one string.
- **Fix 2 (`sdk/core`, `PurchaseSync`):** a purchase already answered is sent again **when the last answer of the backend says that the entitlement of its product is no longer active** (not active, or its end date is not in the future). The backend, not the SDK, still decides access: this only decides whether to ask again. Other purchases keep the rule "once per run", so the limit of 10 receipts a minute of the backend is not spent.
- **Fix 3 (`sdk/android`, `PlayBilling`):** what the Billing Library throws (anything that is not a `KuotaKitException` or a cancellation) becomes `KuotaKitException.StoreUnavailable` or `Unexpected` with a message that holds no token and no user id; a `CancellationException` is never swallowed.
- **Tests:** unit tests for each fix (a deferred answer with the replaced product; the replaced purchase itself does not answer; a renewed purchase is sent again when the backend says expired and is not when it says active; a library exception becomes a `KuotaKitException`). The two end-to-end scenarios first planned were dropped, see the Status.
- **Docs:** an amendment of ADR 0019 (the rule of `restore()` and the plan-change answer), `docs/sdk-android.md`, AGENTS.md, the session log, and the ADR 0020 note "verified against the real Google" for what was read.

## Out of scope
- Google's notifications and a stable public backend (the definitive answer for renewals and refunds with the app closed), a backend that re-reads Google by itself, a time limit for the purchase sheet (the user may leave it open), any new public symbol, iOS.
- The example app (its buttons are fixed by the SDK returning).

## Acceptance criteria
- [x] A purchase update that lists only the **replaced** product, from another token than the replaced one, answers the call of a plan change — checked by: a new unit test; negative check: restoring the old matching must fail it
- [x] The **replaced purchase itself** (its own token) and an update of another account never answer the call — checked by: new unit tests
- [x] While the call waits, the background forwarder does not send what the call answers — checked by: a new unit test
- [x] A purchase already answered is sent again when the backend's last answer says its entitlement is not active, and is **not** when it says active (existing test `aPurchaseTheBackendAlreadyAnsweredIsNotSentAgainButTheCustomerInfoIsReadAgain` keeps passing unchanged) — checked by: new unit tests and that test
- [x] An exception thrown by the Billing Library reaches the app as a `KuotaKitException`, with no token or user id in its message, and a cancellation is not swallowed — checked by: new unit tests
- [x] No public symbol of `sdk/core` or `sdk/android` is added or changed — checked by: `./gradlew :sdk:core:checkKotlinAbi` and the empty diff of `sdk/core/api`
- [x] `./gradlew build` is green and the end-to-end test (`:sdk:e2e:e2eTest` against Docker, the CI job `sdk-e2e`) still passes — checked by: the command, and the CI job `sdk-e2e` in the pull request
- [x] Repeated on the maintainer's phone (a Samsung SM-S938B, license tester, the real Google Play) with a debug build of the example app that has the fixes, driven over adb: the deferred change **returned** (the notice appeared and the buttons were released), and after the monthly plan ended, **Restore** without restarting the app showed `premium_annual` active — checked by: the run of 2026-11-12 02:44-02:51 UTC, written in the session log
- [x] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion

## Steps
- [x] Tests first for fix 1 (they fail with the current code), then the change in `PlayBilling`.
- [x] Tests first for fix 2, then the change in `PurchaseSync`.
- [x] Tests first for fix 3, then the change in `PlayBilling`.
- [x] The negative checks (the tests written first failed with the old code).
- [x] Docs, independent review, fixes.
- [ ] A new bundle of the example app (version code 3) for the maintainer's second run on the phone.

## Validation
- Commands: `./gradlew build :sdk:core:checkKotlinAbi` and the end-to-end test.
- Negative checks planned: the old matching (only the product asked for) fails the new plan-change test; "always dedupe" fails the renewal test; a library exception that is not wrapped fails its test.
- **Not validated until the maintainer's run:** that the reading of Google's answer for a deferred change (the replaced product listed in the new purchase) is the real cause. If the call still hangs after fix 1, a device log (`adb logcat`) of the update that arrives is the next step, and the plan is updated, not the fix guessed again.

## State model
`PlayBilling.waitingFor` goes from a string to one small immutable value (what the waiting call is waiting for: the product asked for, the product replaced and its token), set and cleared in the same two places as today. No new states or transitions. `PurchaseSync` keeps its two sets; the new rule only reads the last customer info.

## Decisions taken
- **Renewals are asked again only when the backend's last answer says the entitlement is not active** (agent): keeps the guard of 10 receipts a minute and the existing test; the alternative (always resend on `restore()`) is simpler but changes that test and spends the limit.
- **No time limit on the purchase wait** (agent): the user can keep the Google screen open for any time; the cause of this hang is the matching, not the lack of a limit.
- **The cause of fix 1 is a hypothesis until the device run** (agent): the plan says so instead of presenting it as a fact.

## Status
Done (the pull request is still to be opened and merged)
- 2026-11-12: written after the first real-Google run of the example app; approved by the maintainer; implementing.
- 2026-11-12: implemented. Tests were written first and failed as they should (4 of the 5 deferred tests and the library-exception test; the second renewal test), then passed: 66 tests in the core and 35 in the Android module, ktlint, detekt, Android lint and the ABI check green with no change of `sdk/core/api`. **Differences from the plan:** (1) the end-to-end scenarios were dropped: the fake Play of `sdk/e2e` is not `PlayBilling`, so a deferred scenario there cannot show the matching, and the renewal rule is deterministic and unit-tested; the real check is the maintainer's run on the phone. (2) The rule of fix 2 uses only the backend's `is_active` (not the end date and the clock): a sample of the tests has a fixed end date that would become past and break an existing test.
- 2026-11-12: independent review (a read-only subagent): nothing blocks. Applied: the cause of the hang is worded as a hypothesis everywhere (code comment, test, ADR 0019 and 0020), and **the device log must show the products and the account id of the update** (if the account id of the new purchase came back different on the client, the call would still wait); a resend because of fix 2 never makes a restore fail with `RateLimited` (it is skipped, with a test); a pending purchase of the replaced product does not answer the call; the message of an unexpected exception names the full class. **Differences:** fix 3 only produces `Unexpected` (the plan also said `StoreUnavailable` for remote exceptions); the observations of the ADR 0020 amendment keep only what was read with the Developer API.
- 2026-11-12 (night): **run on the phone** (debug build with the fixes, over adb, license tester). (1) Monthly bought: "The backend confirmed it", `premium_monthly` active. (2) "Switch to annual when the current plan ends" confirmed with the test card: **the call returned** with its notice and the buttons were released (with the build of Play, which has no fix, they stayed disabled for minutes in the same step); the backend stored the new purchase as the monthly plan until its end, not renewing, and the old row as expired. (3) Right after the monthly plan ended (02:49:55) the app said "not active"; a Restore 25 seconds later said "0 sent, **1 refused**" (Google's answer was still changing: the backend refused it and granted nothing, fail closed); a Restore 30 seconds after that, with no restart, said "1 sent, 0 refused" and showed **`premium_annual` active until 03:19:55, renewing**. Read with the Developer API at that moment: the annual item had its end date and an order, and the monthly item no longer carried `deferredItemReplacement`. **Not confirmed:** why the call hung before (the phone log did not show the products of the update): the fix removes the symptom in the same steps, the cause stays a hypothesis.
