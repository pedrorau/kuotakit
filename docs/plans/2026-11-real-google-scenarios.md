# Plan: every scenario of Google Play billing that the code handles, run against the real Google (V)

- **Origin:** context (the maintainer, 2026-11-13: "preferiria cubrir todos los casos, no dejar ninguno fuera del v0"; the scenarios and times come from [Test your integration](https://developer.android.com/google/play/billing/test), read on 2026-11-13)
- **Approved by the maintainer on:** not yet (the order he set: this validation, then 5b, then the audit, then iOS or other phases)
- **Branch / PR:** `docs/plan-real-google-scenarios` / not yet

## Goal
Phase 1 exits with the criteria of the roadmap, but only a part of what the backend and the SDK handle has met the real Google (purchase, renewal, cancel, refund, deferred plan change, the webhook). The rest was written from the documentation and tested with the fake Google, and the first real run already found three behaviours that the fakes could not (1c). This task runs **every** scenario below on the maintainer's phone with the real Google, records what happened, and turns each difference into a fix with its own plan. Nothing is left out of v0 without a written reason.

## Scope
Each scenario has an id (`V-nn`), what the maintainer prepares, what the agent does, and what must be true. The agent drives the phone over adb, reads the backend log and the `purchases` / `store_events` tables, queries Google read-only (`gq.py`), and checks the Plan screen; the maintainer does what only he can (Play Console, installing Play Billing Lab, anything that needs his Google account). The temporary tunnel stays; the Pub/Sub subscription is updated if the tunnel address changes. Times are Google's test times (renewal 5 min monthly / 30 min annual, max 6 renewals; free trial 3 min; grace 5 min; account hold 10 min; pause 5 min per month).

### A. Subscriptions already set up (no console change)
- **V-01 Immediate plan change** (`CHARGE_PRORATED_PRICE`, monthly → annual and annual → monthly): the call returns, the new plan is active, the old token ends, the entitlement keeps one active product.
- **V-02 Pending purchase, slow card that approves**: Plan stays "not active" while pending, becomes active when Google completes it, with no action of the user except coming back (the SDK's background sync or Restore); nothing acknowledged before it is `PURCHASED`.
- **V-03 Pending purchase, slow card that declines**: no access ever, the purchase is gone, no error escapes the SDK.
- **V-04 Payment declined at renewal → grace period → recovery** (switch the payment method to "always declines" and back, as in the official table): access during grace (the mapper grants it inside the paid period, ADR 0013), the real `expiryTime` during grace is recorded (open question of ADR 0013), access continues after recovery.
- **V-05 Grace → account hold → recovery**: access ends at hold (`on_hold`), returns after recovery.
- **V-06 Account hold without grace → involuntary churn**: ends and stays ended.
- **V-07 Pause** (enabled for license testers even if disabled in the console): `paused` never grants, resumes.
- **V-08 Cancel, then resubscribe from the Play Store** (the "Resuscribirse" button): the purchase has **no** account id (ADR 0017): the backend answers 402, nothing is acknowledged, Google refunds after three minutes for testers; what the user sees must be an honest explanation, not a silent loop. This is the evidence for the decision about softening the rejection.
- **V-09 Test card "approves then charges back"**: the backend learns of the chargeback through the notification (`PendingRefundReviewNotification` and the voided purchase that follows), and the Plan screen shows what the backend says. If the webhook ignores the pending-refund notice, that is written down and decided.
- **V-10 Promo code** (created in Play Console, 500 per quarter): redeemed inside the app's purchase dialog (carries the account id?), in the Play Store app, and in the web store; the result of each against the account-id rule.
- **V-11 Out-of-app purchase** with Play Billing Lab (for subscriptions the card exists; featured products and recommendations are one-time only): the SDK finds it in `restore()` or refuses it as `unattributed`, as ADR 0017 says.
- **V-12 Restart, offline and kill-the-app cases of the SDK**: purchase made, app killed before the receipt is sent; airplane mode during a purchase and a restore; the next start recovers (the store is the queue, ADR 0019); the last-known answer is shown, labelled, never as access.
- **V-13 Renewal limit**: after the sixth renewal the plan ends by itself and the Plan screen shows it on Refresh and by notification.
- **V-14 A second device or a reinstall**: a new anonymous id cannot claim the old purchase (ADR 0005 and 0017: documented behaviour); what the user sees is checked.
- **V-15 Real-time notifications beyond the first run**: a duplicate push (Pub/Sub redelivers when the subscription is not acknowledged: the endpoint is made to answer `500` once), a retry after the backend was down for some minutes, and the order "notification before the receipt" for a refund (ADR 0016).

### B. Needs the maintainer to create something first
- **V-16 One-time product `lifetime_unlock`** (non-consumable): buy it, acknowledged by the backend, entitlement granted for ever, buying again is refused by Google, refund from Play Console, the entitlement ends. Needs the product created and activated, and `entitlements.yaml` and the offering updated (the agent does the YAML).
- **V-17 Free trial and introductory price**: an offer with a free trial (3 min) and another with an introductory price on a base plan; `period_type` is `trial` / `intro` and then `normal`; the app is not told it is a trial by the SDK, the backend is (contracts). Play Billing Lab "test free trial or introductory offer" lets the same account repeat it.
- **V-18 Price change** (Play Billing Lab, price with and without opt-out): the notification types of a price change reach the webhook and are handled (or ignored on purpose) and access does not change; for the KR-only consent step, written as not testable.
- **V-19 Several base plans / offers of one subscription** (a third plan, `weekly`): the offering and the entitlement mapping work with it. Optional if the maintainer does not want to create it.
- **V-20 Regions**: Play Billing Lab country change; the purchase works with another currency and the backend does not depend on the price.

### C. Written down as not testable by us (with the reason, not silently)
- Installment plans and prepaid plans (need eligible regions and commitment terms), real payment methods (Google Business Development must enable it), Play Games rewards and purchase recommendations (one-time products of a game), multi-quantity one-time purchases and partial refunds (out of v0: the backend does not track units, ADR 0015).

## Out of scope
- The stable address and the purge (5b), the audit, iOS, any new public symbol unless a scenario shows a bug (then it gets its own plan and an ADR amendment).

## Acceptance criteria
- [ ] Every scenario V-01 to V-20 has a result in the table of this plan (passed, failed with the fix linked, or blocked with the reason) — checked by: the table, reviewed against the session log
- [ ] Each failure or surprise has a fix (own plan, tests first, review) or a written decision of the maintainer to accept it — checked by: the links in the table
- [ ] Every scenario that is fixed or confirmed that the fake Google got wrong gets a conformance scenario or a unit test that keeps it — checked by: the test names in the table
- [ ] The ADRs whose "not verified against the real Google" lines were confirmed or refuted have an amendment limited to what was observed (0013, 0014, 0015, 0016, 0017, 0019) — checked by: the diff of `docs/adr/`
- [ ] Group C is written in the guide as "not tested, and why" — checked by: `docs/sandbox-play-console.md`
- [ ] Nothing secret is pasted or committed — checked by: `git status` before every commit
- [ ] An independent read-only review of each PR of this task found nothing that blocks — checked by: the review reports

## Steps
- [ ] The maintainer's preparation (Play Billing Lab installed on the phone with the license tester account; `lifetime_unlock`, the trial offer, the introductory offer and, if wanted, the third plan created and active; two promo codes).
- [ ] Group A in order V-01 to V-15, grouped to share waiting times (grace, hold and renewals run in parallel on test subscriptions).
- [ ] Group B.
- [ ] Fixes, one plan per fix, as they appear.
- [ ] ADR amendments, guide, session log, close.

## Results
| Id | Result | Evidence / fix |
|---|---|---|
| V-01 … V-20 | pending | |

## Validation
- For every scenario: the backend log lines, the `purchases` row and `store_events` rows, a read-only query of Google, and a screenshot or text dump of the Plan screen. Negative check where it makes sense: a scenario that "passes" is also run with the fix reverted when the scenario led to a fix.
- Not validated here: real money, a production track, iOS.

## State model
None (no code in this plan; a fix that adds state brings its own).

## Decisions taken
- **Everything the code handles is run, not sampled** (maintainer): the first real run found three SDK defects that tests with fakes could not, so "covered by tests" is not accepted as "verified".
- **Group C is documented, not run** (agent): it needs regions, programmes or units the v0 does not model; each has its reason written.
- **A scenario that shows a real defect stops the group**, gets its own plan and fix, and the group is resumed (agent): the order of the maintainer is kept (this, 5b, audit, iOS).

## Status
Draft
