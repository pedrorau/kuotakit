# Plan: the conformance suite, part 2a: the base

- **Origin:** context (the conformance suite plan of 2026-11-11, split in two by the maintainer's approval of the same day: "si procede con 2 PR")
- **Approved by the maintainer on:** 2026-11-11
- **Branch / PR:** `feat/conformance-suite-base` / not opened yet

## Goal
The foundation of the black-box suite of ADR 0007: a module that runs against the address of any backend, a complete fake Google, the report with levels, and Level 1 (the contract, no Google), proven against the reference backend as shipped in its Docker image and against a mutant proxy that corrupts it. Part 2b (`2026-11-conformance-suite-behaviour.md`) adds Level 2 on top. The maintainer expects third parties soon, so nothing here is a throw-away.

## Scope
- Gradle module `:conformance`, Docker image that runs it, `docker-compose.conformance.yml` (only the `backend` service is replaced by the backend under test, through one variable).
- Runner and report: scenarios have IDs from `conformance/manifest.txt`; the report names failed IDs and ends with "contract conformant (Level 1)" or, when scenarios fail, a non-zero exit; Level 2 is selectable and reported as not run when absent.
- The **complete fake Google** (Play API subscriptions v2, products v2, both acknowledge calls, OAuth token endpoint, signing keys in PEM and JWKS, control API with a call log). The Pub/Sub push signer is part of 2b.
- Fixture generator (key pair, service account key file with `token_uri` at the fake, `entitlements.yaml`, API keys) into a shared volume; nothing secret in git.
- Level 1 scenarios L1-01..L1-12 and R-01, each asserting status, body, the customer info and, where Google is involved, the fake's log; every response validated against `docs/api/openapi.json`.
- Mutant proxy with the Level 1 mutations, and a `self-check` mode of the suite that runs it through each mutation and fails if one goes unnoticed; pluggability checks (a stub backend that only answers health fails and names IDs; Level 1 only run without the custom Google addresses).
- CI job `conformance` in the `needs` of `ci` (`.github/workflows/ci.yml`: job, path filters, `needs`).
- Konsist rule: `conformance` knows nothing of the backend, by import or by Gradle dependency (with canary); it does not even depend on `contracts`, so it stays as foreign to the code as a suite in another language.
- `docs/conformance.md` skeleton (how to run, what your backend needs, plugging in, the manifest of this part), `AGENTS.md` (command and the sentence listing Gradle modules), **ADR 0007 amended with the policy of levels**.

## Out of scope
Level 2 scenarios, the Pub/Sub push signer and the Level 2 mutations (2b); a fake for Apple (Phase 2); publishing the image (needs the maintainer's explicit approval); real Google.

## Scenario manifest of this part
Written and reviewed before any scenario is implemented. The canonical copy is `conformance/manifest.txt` (one ID and a one-line description per line), which the suite reads; this is its first version. It may grow; removing an ID needs a change of this plan or of ADR 0007. Each ID appears in the name of its scenario. A scenario asserts what a client can observe: the status and body of the answer, the customer info that `GET /v1/subscribers/{id}` then returns, and the log of calls that the fake Google keeps (what it was asked, and whether it was asked to acknowledge).

Level 1, the contract, with no Google:
- L1-01 health needs no key · L1-02 a missing, wrong or malformed key is a 401 with the error body, on every authenticated endpoint · L1-03 an app key and a secret key both read · L1-04 the offerings equal the fixture `entitlements.yaml` · L1-05 an unknown user gets an empty customer info · L1-06 invalid app user ids (`..`, 129 characters, a space) are a 400 · L1-07 a body over 16 KiB and malformed bodies are a 400 · L1-08 an unknown path or method is a plain 404 or 405 · L1-09 over the request limit the answer is a 429 with `Retry-After` and the code `rate_limited` · L1-10 a flood of wrong keys does not use up the budget of good keys · L1-11 every response that `docs/api/openapi.json` describes matches it (the plain 404 and 405 and the response headers, such as `Retry-After`, are not described there and are not checked by this scenario) · L1-12 the codes of the error bodies are the ones of the table "Errors" in `docs/api-spec.md`.

Reference backend only, not required of other backends (it is a temporary behaviour that Phase 2 removes): R-01 an App Store receipt is a 400 until Phase 2.

Level 2 IDs are added to `conformance/manifest.txt` by part 2b, which owns them.

## Acceptance criteria
- [x] The suite passes against the reference backend image started by the compose file and prints "contract conformant (Level 1)" — checked by: `docker compose -f docker-compose.conformance.yml -f docker-compose.conformance.reference.yml up --build --abort-on-container-exit --exit-code-from conformance` exits 0 and prints that line (locally on Colima and in CI). The second file only builds the reference image; another backend uses the first file alone.
- [x] Every ID of `conformance/manifest.txt` has a scenario and every scenario has an ID — checked by: a registry test that fails on an ID without scenario or a scenario without ID, and a test that fails if `docs/conformance.md` lists a different set of IDs than the manifest.
- [x] Every described response is validated against `docs/api/openapi.json` — checked by: L1-11, and the mutant that removes a field being caught.
- [x] Each Level 1 mutation of the proxy is detected (a 401 turned into 200, an entitlement granted to an unknown user, a required field removed, `Retry-After` dropped, a 429 turned into 200) — checked by: `CONFORMANCE_COMMAND=self-check` with the same compose files, which exits non-zero if the scenario meant to notice a mutation does not fail. The mutations that need Google (a 402 or a 409 turned into 200, access after a refund) come with Level 2 in 2b.
- [x] A backend that cannot redirect its Google calls can run Level 1 only — checked by: the compose run with the reference backend started without the custom addresses and the lock, exits 0, says "contract conformant (Level 1)".
- [x] A different backend can be plugged in and the report works — checked by: replacing the `backend` service through `CONFORMANCE_BACKEND_IMAGE` by an incomplete image that only answers health: non-zero exit naming the failed IDs (a CI step, and a unit test with an in-process stub).
- [x] The fake Google is complete for part 2b (the Pub/Sub push signer itself is 2b) — checked by: its own tests (`FakeGoogleTest`) over the token endpoint, the Play calls and their defaults, scripted answers, both signing key formats, the control API and the call log.
- [x] Nothing secret is stored — checked by: `git ls-files conformance` lists no key, certificate or service account file.
- [x] The suite never imports the backend — checked by: the Konsist rule and its canary.
- [ ] The CI job is part of `ci` — checked by: `ci.yml` lists it in `needs` of `ci`; a green pull request run shows it.
- [x] The image is built, not published — checked by: `grep -rn "docker push\|ghcr.io\|docker/login-action" .github/workflows` finds nothing.
- [x] ADR 0007 states the policy of levels and the document has the sections Manifest, What your backend needs and Plugging in a backend — checked by: `grep -n "^## " docs/adr/0007-*.md docs/conformance.md`.
- [x] An independent read-only review of the diff against these criteria found nothing that blocks — checked by: the review report (a subagent, read-only). It found five things to fix before merge (a run id that could repeat within a minute, a CI path filter, an unused dependency on `contracts`, a brittle grep in the stub step, a manifest the suite did not read) and some documentation drift; all were fixed and the compose runs and the build repeated.

## Steps
- [x] This plan and the plan of 2b in the repository; the original plan marked Superseded.
- [x] The module, the runner and the report; the fixture generator.
- [x] The fake Google with its control API and tests.
- [x] Level 1 scenarios, the registry and the document tests.
- [x] The mutant proxy and its test.
- [x] The image, the compose file, the CI job.
- [x] The Konsist rule, `docs/conformance.md`, ADR 0007, `AGENTS.md`.
- [ ] Review, commit, push, pull request (the maintainer merges).

## Validation
The compose runs above locally on Colima and in CI; `./gradlew build`; the Konsist canary. Negative checks: removing an OpenAPI validation must let its mutant pass unnoticed; a deliberately broken behaviour through the proxy must turn the suite red.

## State model
None. The fake Google keeps a scripted answer per scenario and forgets it when the scenario ends (a reset, not a set of states).

## Decisions taken
- Two PRs, each green on its own (maintainer, 2026-11-11).
- The policy of levels amends ADR 0007 here, so that the report of this part already speaks it (maintainer approved with the original plan).
- The suite runs the shipped image with the test-only lock of ADR 0018 open; fixtures generated at start; packaged as a Docker image; complete, not modest (original plan).

## Status
In progress
- 2026-11-11: split from the original plan, not started.
- 2026-11-11: implemented on the branch; every criterion checked locally except the green pull request run of the CI job (it needs the pull request). Waiting for the maintainer's OK to commit.
