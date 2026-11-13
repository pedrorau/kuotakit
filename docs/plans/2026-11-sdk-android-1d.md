# Plan: the SDK for Android, subphase 1d (an explicit `restore()` always asks the backend again)

- **Origin:** context (the maintainer's second run on the phone, 2026-11-12: a cancel and a refund made in Google were not shown by Restore without restarting the app; he approved "procede con puntos 2 y 3")
- **Approved by the maintainer on:** 2026-11-12 ("procede con puntos 2 y 3", and "listo mergeado.. procede"); he knew it changes an existing test
- **Branch / PR:** `feat/sdk-android-1d` / not yet

## Goal
`restore()` is what the user presses (or what the app calls when it comes to the foreground) to make the backend look at Google again. 1c made it resend a purchase only when the backend said "not active"; but a **cancel** (`will_renew=false`) or a **refund** leaves the entitlement active or unchanged in the backend's last answer, so Restore did nothing and the screen kept the old state until the app restarted. Without Google's notifications the receipt is the only way the backend learns anything. So an explicit `restore()` sends every completed purchase of this user, every time.

## Scope
- **`sdk/core`, `PurchaseSync`:** `run` takes how it was started. An explicit restore (`KuotaKit.restore()`) sends **every** purchase classified `Send` on every call, and a `RateLimited` answer **propagates** to the caller (an explicit call must not pretend). The syncs the SDK starts itself (at `configure`, for every purchase update, and the one of `purchase` when the store says the user already owns the product) keep today's rule: once per run of the app, plus the 1c rule for what the backend says ended, with `RateLimited` swallowed.
- **Tests:** the three tests whose expectation encodes "restore does not resend" are changed to the new rule (the maintainer approved it), and new ones: an explicit restore sends an active purchase again; a cancelled/refunded answer of the backend shows after a restore without restarting; `RateLimited` reaches an explicit restore; the sync of `purchase` still does not resend.
- **Docs:** amendment of ADR 0019 (the rule of `restore()`), `docs/sdk-android.md`, AGENTS.md, session log, `docs/roadmap.md` only if it mentions it.

## Out of scope
- Google's notifications (the real fix for the app closed), a timer, any new public symbol, a limit of the call rate inside the SDK (the example app and the backend's 10 receipts a minute are the limit; the app should not call `restore()` in a loop).

## Acceptance criteria
- [x] An explicit `restore()` sends a completed purchase of this user every time it is called, whatever the last answer of the backend said — checked by: a new test with a backend that answers "active" every time; negative check: the 1c rule must fail it
- [x] The answer of the second restore replaces the customer info (a cancel seen: `will_renew=false`) — checked by: a new test
- [x] A `RateLimited` answer during an explicit restore reaches the caller as `KuotaKitException.RateLimited` — checked by: a new test
- [x] The syncs the SDK starts itself (background and the one of `purchase`) do not resend what was answered — checked by: new tests through `purchase` and `startBackgroundSync`
- [x] No public symbol is added or changed — checked by: `./gradlew :sdk:core:checkKotlinAbi` and the empty diff of `sdk/core/api`
- [x] `./gradlew build` is green and the end-to-end test still passes — checked by: the command and the CI job `sdk-e2e`
- [ ] On the maintainer's phone, a cancel (and a refund) made in Google shows after pressing Restore, without restarting the app — checked by: the run, written in the session log
- [x] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion

## Steps
- [x] Tests first (they fail with the current code), then the change in `PurchaseSync` and `KuotaKit.restore`.
- [x] Negative check, docs, independent review (it found that the background syncs also used the explicit rule: fixed with the `AUTOMATIC` trigger and a test), fixes.
- [ ] New bundle for the phone run.

## Validation
- `./gradlew :sdk:core:jvmTest :sdk:android:testDebugUnitTest :sdk:core:checkKotlinAbi build` and the end-to-end test.
- Negative check: going back to the 1c rule fails the new restore tests.

## State model
None: `PurchaseSync` keeps its two sets; the only new thing is a parameter (how the sync was started), two values with one meaning each, so it is an enum `SyncTrigger { RESTORE, AUTOMATIC }` and not a boolean.

## Decisions taken
- **Explicit restore always resends and propagates `RateLimited`** (maintainer, 2026-11-12): honest over silent; the alternative (swallow the limit) hides from the user that nothing was checked.
- **The automatic syncs keep the dedup** (agent; found by the review): they run without the user asking and must not spend the limit that an explicit restore needs.

## Status
In progress
- 2026-11-12: approved; implementation started.
