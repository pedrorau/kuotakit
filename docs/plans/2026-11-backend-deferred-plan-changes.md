# Plan: the backend accepts deferred plan changes (ReplacementMode.DEFERRED)

- **Origin:** context (the maintainer asked on 2026-11-12 to analyse whether leaving `DEFERRED` out is acceptable; it is not, see Goal). Reviewed against the official documentation before writing (sources below).
- **Approved by the maintainer on:** 2026-11-12
- **Branch / PR:** `feat/backend-deferred-plan-changes` / #18 (merged)

## Goal
A subscriber who downgrades with Google's recommended mode must keep what they paid for and get the new plan when the old one ends. Today the backend **refuses** such a purchase (`402`, scenario L2-37) and does not acknowledge it, and Google refunds a purchase that is not acknowledged within three days (ADR 0013, ADR 0017): the downgrade the user asked for would be silently cancelled. Google's own table recommends `DEFERRED` for "downgrading to a less expensive tier", so a billing backend that cannot take it is missing expected behaviour. This task makes the backend accept it, fail closed whenever Google's answer is not what we understand. The SDK offers the mode in subphase 1b-ii.

## What the official documentation says (read on 2026-11-12) and what is still unknown
Sources: [Subscriptions: replacement modes and "Handle deferred replacement"](https://developer.android.com/google/play/billing/subscriptions), [`purchases.subscriptionsv2` reference](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).
- **Stated:** with `ReplacementMode.DEFERRED` "the new purchase is issued immediately with two items: the existing item with auto renew disabled and expiry time set to the end of the current billing cycle, and the new entitlement which begins after the existing item expires". A `SUBSCRIPTION_PURCHASED` notification with the **old** product id is sent right after the purchase flow, `subscriptionsv2.get` of the new token returns two line items, and "with `ReplacementMode.DEFERRED`, first renewals follow the standard behavior of any other renewal": the new purchase is processed and acknowledged when that notification arrives (that is, now).
- **Stated in the reference:** the line item being replaced carries `deferredItemReplacement.productId` ("the productId going to replace the existing productId"); `latestSuccessfulOrderId` is "not present if the item is not owned by the user yet (e.g. the item being deferred replaced to)"; `expiryTime` is "time at which the subscription expired or will expire unless the access is extended".
- **Not documented, to verify against the real Google (the example app task):** whether the new item has an `expiryTime` before it starts, what the two items look like after the first renewal (does the old one stay, expired?), and the `subscriptionState` of the purchase in between. This plan encodes the reading above in the fake Google and the mapper; anything that does not match it is refused as today (fail closed), so a wrong reading costs a refused purchase, never access.
- The Google client library of the backend already has `DeferredItemReplacement` in its model.

## Scope
- **Domain:** `PlaySubscriptionItem` gains what Google says about ownership and deferral: the product that will replace it (`deferredItemReplacement.productId`) and whether the user owns the item yet (`latestSuccessfulOrderId` present; a lone item counts as owned). `GooglePlaySubscriptions` reads them. **The mapper** decides the **current item** of a purchase in one place: the owned item that has not expired. A receipt (or a stored purchase, on a notification) that names a product is accepted if that product is the current item, or if it is the product that the current item is deferred to be replaced by. An item the user does not own yet is never current and an ambiguous purchase (two items that match) is refused. The stored purchase is the current item: its `productId` and `expiresAt` are the old plan's during the deferral (auto-renew off, `will_renew` false) and the new plan's after it takes effect, which a notification, a restore or a new receipt brings (the product of the stored purchase changes with the same row: `save` already updates it).
- **The replaced purchase** (`linkedPurchaseToken`, ADR 0015) is expired as today; the user does not lose access because the new token carries the old item until its end.
- **Notifications:** a renewal of the deferred purchase is read again and the mapper picks the new current item; if Google no longer lists the stored product, the single item the user owns is used.
- **Fault tolerance and mutation tests** keep covering it (the existing fault-injection test gets a deferred scenario; Pitest keeps 70 %).
- **Conformance suite:** the fake Google can script a deferred purchase; scenario **L2-37 stays** (it scripted a new line item with no end date and no old item to follow, which is still refused, and its description is narrowed to say so) and new scenarios L2-38 to L2-41 cover: the deferred purchase is accepted and acknowledged once, the old plan keeps giving access, the entitlement switches to the new product after the renewal, a deferred purchase whose current item cannot be determined is refused, and a deferred purchase of another user is a 409. The manifest and the docs are updated; the Level 2 mutations of the proxy are unchanged (they still apply). No documented expectation changes (see the Status).
- **ADR 0020** "Deferred plan changes", `docs/api-spec.md` (a line), `docs/conformance.md`, AGENTS.md, plan and session log.

## Out of scope
- Subscriptions with add-ons (`KEEP_EXISTING`, several owned items), `deferredItemRemoval`, prepaid plans and installments.
- The SDK side (1b-ii), and the check against the real Google (the example app task, where the reading above is confirmed or corrected).

## Acceptance criteria
- [x] A receipt for the product a deferred purchase will switch to is accepted: `200`, acknowledged once, the entitlement of the old product keeps giving access until its end with `will_renew` false — checked by: domain tests (mapper, `ReceiptService`) and the conformance scenario.
- [x] After the renewal that makes the new item current, a notification, a restore or the same receipt gives the new product with its own end date, and the old plan no longer gives access — checked by: domain tests and a conformance scenario.
- [x] A purchase whose items are not understood (no owned item, two owned items, an owned item that expired, the product asked for is neither current nor the deferred one) is refused with `402` and nothing is acknowledged — checked by: a test per case.
- [x] The rules of ownership still hold: no account id `402`, another user's `409`, a refunded or revoked purchase stays final — checked by: the existing tests plus a deferred variant of each.
- [x] A failure of any dependency at any step of the deferred flow is raised and a retry leaves the normal result — checked by: the fault-injection test with a deferred scenario.
- [x] The conformance suite passes against the reference backend ("fully conformant") with the changed and new scenarios, and the mutants and negative checks fail as they should — checked by: the compose run, `self-check`, and breaking the mapper on purpose (picking the replaced item, ignoring ownership, accepting without acknowledging).
- [x] Mutation score of `domain` stays at 70 % or more — checked by: `./gradlew :backend:pitest` (CI scopes it to the changed classes).
- [x] The change of L2-37 is visible — checked by: the diff of `conformance/manifest.txt` and `docs/conformance.md`, and this plan.
- [x] ADR 0020 states the reading of Google's answer, what is unverified and how it fails — checked by: `grep -n "^## " docs/adr/0020-*.md` and a review.
- [x] `./gradlew build` and the conformance compose run pass — checked by: the commands and a green pull request run.
- [ ] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report.

## Steps
- [x] Domain: the item fields, the "current item" rule in the mapper, the notification path.
- [x] The Google mapping and the fake Google scripts; backend tests, fault tolerance, Pitest.
- [x] Conformance scenarios, manifest, docs and mutations.
- [x] ADR 0020, api-spec, AGENTS.md, plan and session log.
- [ ] Review, commit, push, pull request (the maintainer merges).

## Validation
- `./gradlew build` (real Postgres through Colima), `./gradlew :backend:pitest` on the changed classes, the conformance compose run and `self-check`.
- Negative checks of the criteria above. Not validated: the real Google.

## State model
The purchase keeps one row per token, as today. The new fact is explicit, not inferred from several fields: **which item of the purchase is current** is decided by one function of the mapper from the items (owned, expired, deferred-to), and stored as the purchase's `productId` and `expiresAt`. `PlaySubscriptionItem` gets its two new fields as plain data of Google's answer (a nullable product id and a boolean for ownership), with no transitions of their own. The status values do not change.

## Decisions taken
- **Support `DEFERRED` in v0** (maintainer, 2026-11-12, after the analysis above): it is Google's recommended mode for downgrades, and refusing it makes Google cancel the change after three days.
- **Fail closed on any reading we cannot confirm** (agent): the unknown parts of Google's answer are exactly where a wrong guess could grant access; they are refused as today until the real Google confirms them.
- **No new column or table** (agent): the row of the token already updates its product and end date.
- **Order of work** (agent): this task comes before subphase 1b-ii, so that the end-to-end test of the SDK can include a deferred change against the real backend.

## Status
Done
- 2026-11-12: written after reading the documentation above; approved by the maintainer ("procede"); implementation started.
- 2026-11-12: implemented on the branch; every criterion checked locally. The mapper rule turned out simpler than planned: it needs no "owned" flag (`latestSuccessfulOrderId`), only the item that is replaced and the end dates, so only `deferredReplacement` was added. Difference from the plan: **L2-37 was not changed**, because it scripted a single line item without end date and no old item, which is still refused; its description is narrowed and L2-38 to L2-41 were added, so there is no change of an expectation. The purchase is acknowledged under the product it has now (found while designing: Google sends the notification of the new token with the old product id). Negative checks on the real backend, each run and reverted: the deferred item never current (L2-38, L2-39 failed), the product of the receipt stored instead of the current one (L2-38), a replaced item not followed after it ended (L2-39); a fourth (an old item that ended is still current) did not fail the suite because the chain-following gives the same outcome: an equivalent change, not a gap. Mutation testing of the two changed classes: 84 % (131 mutations, 110 killed; the survivor of the end-date boundary of the first rule is equivalent for the same reason). Waiting for the independent review and the maintainer's OK to commit.
- 2026-11-12: independent review (a read-only subagent): no way found to gain access to a product that is not an item of the same token and account. Findings fixed: the mapper did not use Google's "not owned" signal (now `ownedByUser`, and L2-40 depends on it), the choice depended on the order of the items (ambiguous is refused), after the change took effect a notification could leave the user on the old plan if Google stopped listing it (the single owned item is used), the conformance assertions were weak (acknowledgement counted after the second receipt and after the renewal, the old plan no longer active after it), a revocation variant and more cases were added. The two simplifications asked for were not made: the end-date check of the first rule is equivalent to the chain-following and is kept for clarity. Recorded in ADR 0020 as unverified: the acknowledge call with the old product and the new token, and what Google reports for the old token. The plan's old statement about the Level 2 mutations and about changing L2-37 was corrected. Waiting for the maintainer's OK to commit.
- 2026-11-11: merged as #18; the pull request run and the post-merge run of `main` were green.
