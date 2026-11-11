# Plan: the conformance suite (part 2)

- **Origin:** context (proposed by the agent and approved in the conversation of 2026-11-11, copied here)
- **Approved by the maintainer on:** 2026-11-11
- **Branch / PR:** none: split into the two plans below

## Goal
A black-box suite that runs against the address of **any** backend and checks its behaviour, not its code (ADR 0007). It must prove that the reference backend, as it is shipped in its Docker image, keeps the contract, and it must be usable by anyone who writes a backend in another language. It has to be complete from the start, not a modest first version: the maintainer expects third parties to try it soon.

## Scope
- A Gradle module `:conformance` and a Docker image that runs it, so that a third party needs neither Kotlin nor Gradle, plus `docker-compose.conformance.yml` where only the `backend` service is replaced by the backend under test.
- **A fake Google** (the central piece): the Play Developer API (subscriptions v2, products v2, both acknowledge calls), the OAuth token endpoint, the signing keys of the Pub/Sub tokens in both formats (PEM map and JWKS), a signer for Pub/Sub pushes, and a control API that scripts its answers for each scenario.
- **Fixtures generated at start** (key pair, service account key file with `token_uri` pointing at the fake, `entitlements.yaml`, API keys) into a shared volume, so that no secret is stored in the repository.
- **Level 1, the contract, with no Google:** API keys (missing, wrong, malformed, app and secret), the error body of every status, `429` with `Retry-After`, offerings from the fixture, an unknown user, app user id and body-size validation, health, and **every response validated against `docs/api/openapi.json`**.
- **Level 2, the behaviour of the stores:** valid subscription (normal, trial, introductory), expired, in grace, on hold, paused, cancelled until its end, pending and unknown states; one-time purchases (including refunded and a missing refundable quantity); the account id missing, another user's, or in plain text; one token claimed by two users; failures of Google (token unknown, permission, quota, outage, connection refused) that never grant access; and the whole webhook: invalid tokens (audience, account, expiry, signature), test messages, refund, refund before the receipt, duplicates, another app, one-time notifications and plan changes.
- **A check of the suite itself:** a "mutant" proxy that corrupts the answers of the reference backend (a 402 turned into 200, access granted, a field removed, `Retry-After` dropped) and a test that each corruption is caught.
- A CI job `conformance` (with the compose file), part of the required `ci` check: this edits `.github/workflows/ci.yml` (the job, its path filters, and the `needs` list of the `ci` job).
- A Konsist rule in `architecture-tests/`: `conformance` may import `contracts` but never the backend, or it would stop being black-box (with its canary).
- Documentation: `docs/conformance.md` (how to run it against your backend and what your backend needs, from ADR 0018), the command in `AGENTS.md`, and **ADR 0007 amended with the policy of levels**: a backend that passes Level 1 is "contract conformant"; one that also passes Level 2 is "fully conformant"; the maintainer accepts a second backend as an official one only if it is fully conformant. This is a change of the policy of ADR 0007 ("passes the full suite"), approved with this plan.

## Out of scope
- A fake for Apple and the App Store scenarios (Phase 2); the structure leaves room for one fake per store.
- Publishing the image to a registry: an outward-facing action that needs the maintainer's explicit approval.
- Long timeouts of Google (slow); connection refused and error answers are covered.
- The real Google (validated at the end of Phase 1 with an app on an internal test track).

## Scenario manifest (first version)
Written and reviewed before any scenario is implemented. The canonical copy is `conformance/manifest.txt` (one ID and a one-line description per line), which the suite reads; this is its first version. It may grow; removing an ID needs a change of this plan or of ADR 0007. Each ID appears in the name of its scenario. A scenario asserts what a client can observe: the status and body of the answer, the customer info that `GET /v1/subscribers/{id}` then returns, and the log of calls that the fake Google keeps (what it was asked, and whether it was asked to acknowledge).

Level 1, the contract, with no Google:
- L1-01 health needs no key · L1-02 a missing, wrong or malformed key is a 401 with the error body, on every authenticated endpoint · L1-03 an app key and a secret key both read · L1-04 the offerings equal the fixture `entitlements.yaml` · L1-05 an unknown user gets an empty customer info · L1-06 invalid app user ids (`..`, 129 characters, a space) are a 400 · L1-07 a body over 16 KiB and malformed bodies are a 400 · L1-08 an unknown path or method is a plain 404 or 405 · L1-09 over the request limit the answer is a 429 with `Retry-After` and the code `rate_limited` · L1-10 a flood of wrong keys does not use up the budget of good keys · L1-11 every response that `docs/api/openapi.json` describes matches it (the plain 404 and 405 and the response headers, such as `Retry-After`, are not described there and are not checked by this scenario) · L1-12 the codes of the error bodies are the ones of the table "Errors" in `docs/api-spec.md`.

Reference backend only, not required of other backends (it is a temporary behaviour that Phase 2 removes): R-01 an App Store receipt is a 400 until Phase 2.

Level 2, the behaviour of the stores (the fake Google):
- Subscriptions: L2-01 a valid one grants its entitlement and the fake Google's log shows one acknowledge · L2-02 a free trial and an introductory price show in the period type · L2-03 an expired one, and the boundary of its end, deny · L2-04 a grace period grants and dates the billing problem · L2-05 on hold and paused deny, and when the fake later reports the subscription active again and the receipt is sent again, access returns · L2-06 cancelled until its end grants and does not renew · L2-07 pending and unknown states are a 402, the customer info stays empty and the log shows no acknowledge · L2-08 a product that does not match the receipt is a 402 (the mapper refuses it, `PlaySubscriptionMapper`) · L2-09 one the fake reports as already acknowledged is not acknowledged again · L2-10 a failing acknowledgement is a 502, the customer info stays empty, and once the fake stops failing the same receipt is a 200 with one acknowledge in the log.
- One-time purchases: L2-11 a valid one grants with no end date and is listed as a non-subscription purchase · L2-12 a refunded one (nothing left to refund) denies · L2-13 a missing refundable quantity is a 402 · L2-14 pending and cancelled ones are a 402.
- Account id: L2-15 a subscription without it is a 402, the customer info stays empty and the log shows no acknowledge · L2-16 another user's is a 409 · L2-17 the plain id, another case or added spaces are a 409 · L2-18 one token claimed by two users: the second is a 409 and the first still has the access (what a client can observe; which internal rule refuses it is not part of the contract) · L2-35 a one-time purchase without the account id is a 402 · L2-36 a one-time purchase with another user's is a 409.
- Replacement: L2-19 an upgrade ends the old plan, and a refused new purchase leaves the old one alone · L2-37 a deferred replacement (the new line item has no end date) is a 402 and leaves the old plan alone.
- Failures of Google: L2-20 400, 404 and 410 when reading are a 402 · L2-21 401, 403, 429, 500 and 503 are a 502 and grant nothing · L2-22 a refused connection is a 502 · L2-23 a receipt for another package is a 400 and the log shows that Google was not asked.
- Webhook: L2-24 a push without proof, or with a wrong audience, account, unverified email, issuer, expired, badly signed or `alg: none` token, is a 401 and the customer info does not change · L2-25 a test message is a 204 · L2-26 a refund removes access for good and a receipt sent again does not restore it · L2-27 a revocation does the same · L2-28 a refund before the receipt: the receipt that comes later is answered with no access, and the log shows no acknowledge · L2-29 a duplicate message is handled once (the log shows one read) · L2-30 a notification of another app is a 204 and the log shows that Google was not asked · L2-31 a renewal is read again and extends the access · L2-32 one-time cancellation and refund notifications · L2-33 an unreadable body is a 204, and unreachable signing keys are a 502 · L2-34 a notification for a purchase nobody submitted is a 204, the customer info stays empty, and a receipt that comes later behaves as a new one.

## Acceptance criteria
- [ ] The suite passes against the reference backend image started by the compose file, and says "fully conformant" — checked by: `docker compose -f docker-compose.conformance.yml up --abort-on-container-exit --exit-code-from conformance` exits 0 and prints that line (run locally on Colima and in CI).
- [ ] Every ID of `conformance/manifest.txt` has a scenario and every scenario has an ID — checked by: a test over the suite's scenario registry that fails on an ID without a scenario or a scenario without an ID; and a second test that fails if `docs/conformance.md` lists a different set of IDs than the manifest.
- [ ] Every response of every scenario is validated against `docs/api/openapi.json` — checked by: scenario L1-11, and a mutant that drops a field being caught.
- [ ] Each mutation of the proxy is detected — checked by: the suite's own test, which runs the suite through each mutation (a 402 turned into 200, access granted, a field removed, `Retry-After` dropped, a 409 turned into 200) and fails if any of them goes unnoticed.
- [ ] A backend that cannot redirect its Google calls runs Level 1 only and is reported as "contract conformant" — checked by: running the compose file with the reference backend started without the custom Google addresses and without the lock, and the suite asked for Level 1 only: it exits 0, prints "contract conformant (Level 1)" and says that Level 2 was not run.
- [ ] A different backend can be plugged in, and the report works — checked by: running the compose file with the `backend` service replaced through one variable by a deliberately incomplete image that only answers the health check must exit non-zero and name the failed scenario IDs.
- [ ] Nothing secret is stored in the repository: the fixtures are generated at start — checked by: `git ls-files conformance` lists no key, certificate or service account file.
- [ ] The suite never imports the backend — checked by: the Konsist rule in `architecture-tests/` and its canary.
- [ ] The CI job runs and is part of the `ci` check — checked by: `.github/workflows/ci.yml` lists it in the `needs` of `ci`, and a green pull request run shows it.
- [ ] The image is built but not published — checked by: `grep -rn "docker push\|ghcr.io\|docker/login-action" .github/workflows` finds nothing.
- [ ] ADR 0007 states the policy of levels and `docs/conformance.md` has the manifest and the steps for a third-party backend — checked by: `grep -n "^## " docs/adr/0007-*.md docs/conformance.md` lists a section for the levels in the ADR and the sections Manifest, What your backend needs and Plugging in a backend in the document, and a review of each section against this plan.
- [ ] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion.

## Steps
- [ ] The scenario list, written first from the contract and the ADRs, and reviewed.
- [ ] The fake Google and the fixture generator.
- [ ] The Level 1 scenarios, then the Level 2 scenarios.
- [ ] The mutant proxy and the test of the suite itself.
- [ ] The image, the compose file, the CI job (edit of `.github/workflows/ci.yml`).
- [ ] The Konsist rule in `architecture-tests/`, `docs/conformance.md`, the amendment of ADR 0007, and `AGENTS.md` (the command and the sentence that lists the modules of the Gradle build, which gains `:conformance`).
- [ ] Review, commit, push, pull request, merge (the maintainer merges).

## Validation
- The compose run above, locally on Colima and in CI; `./gradlew build`; the Konsist canary.
- Negative checks: a deliberately broken backend behaviour (through the mutant proxy) must turn the suite red; removing a validation against the OpenAPI document must make the corresponding mutant pass unnoticed, proving the validation matters.

## State model
None. (The fake Google keeps a scripted answer per scenario and forgets it when the scenario ends; that is a reset, not a set of states.)

## Decisions taken
- A fake Google at a configurable address, in two levels: the contract that any backend must keep, and the behaviour that needs the fake. A backend that cannot redirect its Google calls can only be "contract conformant", not "fully conformant" (agent, approved by the maintainer).
- Packaged as a Docker image, so that it works for any language (agent).
- The suite runs the shipped image with the test-only lock of the profile open, on purpose: it is the only difference from production, and it is written down in `docs/conformance.md` (agent).
- The policy of levels amends ADR 0007 (maintainer approved it with this plan).
- Fixtures generated at start instead of committed (agent; also the repository's guard hooks would refuse to store such files).
- Complete from the first version, not modest (maintainer, 2026-11-11).

## Status
Superseded by `2026-11-conformance-suite-base.md` (2a) and `2026-11-conformance-suite-behaviour.md` (2b)
- 2026-11-11: approved, not started. It comes after `2026-11-conformance-profile.md`.
- 2026-11-11: split in two (maintainer: "si procede con 2 PR"); the scenarios and criteria moved to the two plans. Kept as the record of the whole.
