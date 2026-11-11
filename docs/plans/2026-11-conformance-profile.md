# Plan: conformance profile of the backend (part 1 of the conformance suite)

- **Origin:** context (proposed by the agent and approved in the conversation of 2026-11-11, copied here)
- **Approved by the maintainer on:** 2026-11-11
- **Branch / PR:** `feat/conformance-profile` / not started

## Goal
Let a backend talk to a Google that lives at another address, **only when explicitly allowed**, so that the conformance suite can stand a fake Google in front of any backend (ADR 0007), and write down the *conformance profile*: what a backend must offer to be tested by the suite, in any language.

## Scope
- Two new settings of the reference backend: `KUOTAKIT_GOOGLE_API_BASE_URL` (the root address of the Play Developer API) and `KUOTAKIT_GOOGLE_CERTS_URL` (where the signing keys of the Pub/Sub tokens are fetched).
- A lock: `KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS=true` is required to use either setting; without it the backend refuses to start and says why. With it, the backend logs a warning at start.
- The token endpoint of the service account needs **no setting**: the Google auth library takes it from the `token_uri` of the service account key file, which the suite generates.
- ADR 0018 defining the conformance profile: the settings, the lock, the fixtures a backend is started with (a draft until the suite generates them, which may amend it), the Google endpoints it calls, the formats of the signing keys the fake serves (a PEM map and a JWKS, at two addresses: a backend points `KUOTAKIT_GOOGLE_CERTS_URL` at the one its own library reads), and the start-up contract (health, the API keys and the low request limits the scenarios need).
- Documentation of the settings as test-only (`docs/google-play-setup.md`, `.env.example`, `docker-compose.google.yml`).

## Out of scope
- The suite itself, the fake Google and the CI job (part 2).
- A fake for Apple (Phase 2).
- Publishing any image to a registry.

## Acceptance criteria
- [x] With `KUOTAKIT_GOOGLE_API_BASE_URL` set, **every** call of the Play Developer API goes to that address: reading a subscription, acknowledging it, reading a one-time purchase and acknowledging it (they share one client) — checked by: tests in `backend.google` that start a local server and assert the host and path of each of the four calls.
- [x] With `KUOTAKIT_GOOGLE_CERTS_URL` set, the Pub/Sub token verifier fetches its keys from that address (the reference backend reads the PEM-map format) — checked by: a test with a token signed by a throw-away key and a local certs server; the JWKS address is documented for other backends and not required of this one.
- [x] The service account token endpoint is read from `token_uri` of the key file, with no new setting — checked by: a test where a key file points `token_uri` at a local server and the client obtains its token there.
- [x] Either setting without `KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS=true` stops the start-up with a message that names the variables; with it, the backend starts and logs a warning — checked by: configuration tests for the refusal, and `WiringTest` for the warning (a log capture that also shows that there is no warning without the addresses).
- [x] A value that is not an `http` or `https` address with a host is reported with the name of its variable — checked by: configuration tests.
- [x] Without the new settings Google's own addresses are used — checked by: a test on the default client construction.
- [x] `Main.kt` passes the settings to the Google classes — checked by: a test in this pull request that calls the wiring of the services (extracted from `main` into a function a test can call) with the settings and the lock, and reads a purchase through the resulting services from a local server. The compose run of part 2 is extra evidence, not the check.
- [x] ADR 0018 exists and has the sections Settings, Lock, Fixtures, Google endpoints, Signing key formats and Start-up contract, each with its content — checked by: `grep -n "^## " docs/adr/0018-*.md` lists them, and a review of each against `docs/plans/2026-11-conformance-suite.md`.
- [x] The places that document the settings say they are for testing only — checked by: `grep -rln "ALLOW_CUSTOM_GOOGLE_URLS" docs .env.example docker-compose.yml docker-compose.google.yml` lists the ADR, `docs/google-play-setup.md`, `.env.example` and `docker-compose.google.yml` (the Google settings live in the latter, not in `docker-compose.yml`; the first version of this criterion named the wrong compose file).
- [ ] The build and the checks of the pull request are green — checked by: `./gradlew build` (with the Colima variables) and the CI run.
- [x] An independent read-only review of the diff against the criteria above found nothing that blocks — checked by: the review report, which goes through each criterion (one round: its findings were applied, see the session log).

## Steps
- [x] Write the ADR 0018 draft (the profile).
- [x] Add the settings and the lock to `AppConfig`, with validation and tests.
- [x] Extract the wiring of the services from `main` into a function that a test can call (ADR 0014 listed the wiring as untested).
- [x] Use them in the `backend.google` package, the only one that imports Google libraries: `GooglePlayStoreApi` builds the client that also serves one-time purchases and the acknowledgements, and `GooglePushTokenVerifier`; wire them in `Main.kt`.
- [x] Tests for the three redirections and for the lock.
- [x] Docs, `.env.example`, compose.
- [ ] Review, commit, push, pull request, merge (the maintainer merges).

## Validation
- `./gradlew build` with the Colima variables (see AGENTS.md); `python3 scripts/ci/check_license_headers.py`.
- Pitest scoped to the changed classes of `domain`, if any (the change is mostly in `config` and `google`, which are not mutated).
- Negative checks (break on purpose, the new tests must fail, then restore): ignore the base URL setting; remove the lock; point the `token_uri` of the key file made by the test helper at a dead address (the token test must fail: the library must honour `token_uri`, since this code has no line of its own to break).

## State model
None.

## Decisions taken
- Option B of the conversation: a configurable Google address, instead of a suite that only checks the contract (maintainer, 2026-11-11: complete, expecting third parties to test backends in other languages).
- The lock is an explicit setting, so that a mistake in production cannot point a backend at a fake server (agent, approved).
- No setting for the token endpoint, because the key file already carries it (agent, verified by reading the auth library).
- The signing keys are served in two formats because different libraries read different ones; each backend points its setting at the format it reads (agent).
- ADR 0018 is written before the fixture generator exists, so its fixture part is a draft that the suite pull request may amend (agent).

## Status
In progress
- 2026-11-11: approved; the documentation pull request that creates `docs/plans/` is merged.
- 2026-11-11: implemented and reviewed; every criterion is ticked only after its check was run. Left: the pull request checks, and the pull request itself.
