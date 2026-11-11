# Plan: the conformance suite, part 2b: the behaviour of the stores

- **Origin:** context (the conformance suite plan of 2026-11-11, split in two by the maintainer's approval of the same day: "si procede con 2 PR")
- **Approved by the maintainer on:** 2026-11-11
- **Branch / PR:** `feat/conformance-suite-behaviour` / not started

## Goal
Level 2 on top of the base of part 2a (`2026-11-conformance-suite-base.md`, merged first): the scenarios that check how a backend behaves against the stores, so that "fully conformant" means something and a third party can earn it.

## Scope
- The scenarios L2-01..L2-37 below, added to `conformance/manifest.txt` and to `docs/conformance.md`.
- The Pub/Sub push signer in the fake Google (valid and invalid tokens: wrong audience, account, unverified email, issuer, expired, badly signed, `alg: none`).
- The Level 2 mutations of the proxy (access granted after a refund, a missing acknowledge, a 502 turned into 200) and their test.
- The report says "fully conformant" when Levels 1 and 2 pass.
- Full third-party documentation: what each scenario proves, how to script the fake Google from another language, how to read a failure.
- The CI job from 2a runs both levels.

## Out of scope
Apple (Phase 2); publishing the image; long Google timeouts; real Google.

## Scenario manifest of this part

Level 2, the behaviour of the stores (the fake Google):
- Subscriptions: L2-01 a valid one grants its entitlement and the fake Google's log shows one acknowledge · L2-02 a free trial and an introductory price show in the period type · L2-03 an expired one, and the boundary of its end, deny · L2-04 a grace period grants and dates the billing problem · L2-05 on hold and paused deny, and when the fake later reports the subscription active again and the receipt is sent again, access returns · L2-06 cancelled until its end grants and does not renew · L2-07 pending and unknown states are a 402, the customer info stays empty and the log shows no acknowledge · L2-08 a product that does not match the receipt is a 402 (the mapper refuses it, `PlaySubscriptionMapper`) · L2-09 one the fake reports as already acknowledged is not acknowledged again · L2-10 a failing acknowledgement is a 502, the customer info stays empty, and once the fake stops failing the same receipt is a 200 with one acknowledge in the log.
- One-time purchases: L2-11 a valid one grants with no end date and is listed as a non-subscription purchase · L2-12 a refunded one (nothing left to refund) denies · L2-13 a missing refundable quantity is a 402 · L2-14 pending and cancelled ones are a 402.
- Account id: L2-15 a subscription without it is a 402, the customer info stays empty and the log shows no acknowledge · L2-16 another user's is a 409 · L2-17 the plain id, another case or added spaces are a 409 · L2-18 one token claimed by two users: the second is a 409 and the first still has the access (what a client can observe; which internal rule refuses it is not part of the contract) · L2-35 a one-time purchase without the account id is a 402 · L2-36 a one-time purchase with another user's is a 409.
- Replacement: L2-19 an upgrade ends the old plan, and a refused new purchase leaves the old one alone · L2-37 a deferred replacement (the new line item has no end date) is a 402 and leaves the old plan alone.
- Failures of Google: L2-20 400, 404 and 410 when reading are a 402 · L2-21 401, 403, 429, 500 and 503 are a 502 and grant nothing · L2-22 a refused connection is a 502 · L2-23 a receipt for another package is a 400 and the log shows that Google was not asked.
- Webhook: L2-24 a push without proof, or with a wrong audience, account, unverified email, issuer, expired, badly signed or `alg: none` token, is a 401 and the customer info does not change · L2-25 a test message is a 204 · L2-26 a refund removes access for good and a receipt sent again does not restore it · L2-27 a revocation does the same · L2-28 a refund before the receipt: the receipt that comes later is answered with no access, and the log shows no acknowledge · L2-29 a duplicate message is handled once (the log shows one read) · L2-30 a notification of another app is a 204 and the log shows that Google was not asked · L2-31 a renewal is read again and extends the access · L2-32 one-time cancellation and refund notifications · L2-33 an unreadable body is a 204, and unreachable signing keys are a 502 · L2-34 a notification for a purchase nobody submitted is a 204, the customer info stays empty, and a receipt that comes later behaves as a new one.


## Acceptance criteria
- [ ] The suite passes against the reference backend and prints "fully conformant" — checked by: the compose run of 2a, now exiting 0 with that line (locally on Colima and in CI).
- [ ] Every L2 ID has a scenario and appears in the manifest and in `docs/conformance.md` — checked by: the registry and document tests of 2a.
- [ ] Each Level 2 mutation is detected — checked by: the suite's own mutant test.
- [ ] The push signer produces every invalid token class and the reference backend refuses each — checked by: L2-24 and the signer's own tests.
- [ ] Scenarios assert observable effects only (response, customer info, fake call log) — checked by: review of each scenario and the Konsist rule of 2a.
- [ ] A negative check per group: breaking the reference backend on purpose (e.g. dropping the account id check, applying a refund as non-final) turns the matching scenarios red — checked by: running the suite against the broken backend once, then reverting.
- [ ] Documentation lets a third party run and read the suite — checked by: `grep -n "^## " docs/conformance.md` and a review.
- [ ] An independent read-only review found nothing that blocks — checked by: the review report.

## Steps
- [ ] Push signer and its tests.
- [ ] L2 scenarios by group: subscriptions, one-time, account id, replacement, Google failures, webhook.
- [ ] Level 2 mutations and the test.
- [ ] Documentation and `AGENTS.md` current state.
- [ ] Review, commit, push, pull request (the maintainer merges).

## Validation
Compose run locally on Colima and in CI; `./gradlew build`; the negative checks above.

## State model
None.

## Decisions taken
- Scenarios assert only what a client can observe; which internal rule refuses a request is not part of the contract (original plan).

## Status
Approved (starts when 2a is merged)
- 2026-11-11: split from the original plan.
