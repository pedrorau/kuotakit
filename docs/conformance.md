# Conformance suite

The conformance suite is a program that talks to the address of **any** KuotaKit backend and checks what it answers, not how it is written (ADR 0007). The reference backend (Kotlin) passes it in CI as a Docker image; a backend written in another language is a valid KuotaKit backend when it passes it. It needs no Kotlin and no Gradle from you: it is a Docker image.

## Levels
- **Level 1, the contract.** What every backend does with no store involved: API keys, the error body of every status, request limits, offerings, an empty customer info, validation, and every answer matching [`api/openapi.json`](api/openapi.json). A backend that passes it is **contract conformant (Level 1)**.
- **Level 2, the behaviour of the stores** (a later release of the suite): receipts, refunds, the webhook and the failures of Google, against a fake Google that the suite scripts. A backend that passes both levels is **fully conformant**. The maintainer accepts a second backend as an official one only if it is fully conformant (ADR 0007).

A backend that cannot send its Google calls to another address can only be contract conformant: Level 1 never makes the backend ask Google (the compose file still starts the fake Google and gives the backend the Google settings, which it may ignore).

## Running it against the reference backend
```bash
./gradlew :backend:installDist :conformance:installDist
docker compose -f docker-compose.conformance.yml -f docker-compose.conformance.reference.yml \
  up --build --abort-on-container-exit --exit-code-from conformance
```
It exits `0` and ends with `Result: contract conformant (Level 1)`. Any failure exits `1` and names the scenarios: `Result: NOT conformant: failed L1-02, L1-09`.

## Running it against your backend
The compose file starts four services: a Postgres (in case your backend uses one), the **fake Google**, **your backend** and the suite. Only `backend` is yours:
```bash
CONFORMANCE_BACKEND_IMAGE=my-backend:dev \
  docker compose -f docker-compose.conformance.yml up --abort-on-container-exit --exit-code-from conformance
```
Your image must start a server on port `8080` and read its settings from the environment, with the names of the table below (they are the ones of the reference backend, ADR 0013 to 0018). Level 1 only, for a backend that cannot redirect Google: add `CONFORMANCE_LEVEL=1 CONFORMANCE_CUSTOM_GOOGLE= CONFORMANCE_GOOGLE_API_URL= CONFORMANCE_GOOGLE_CERTS_URL=`.

## What your backend needs
- `GET /health` answers `200` within two minutes of starting.
- The settings of the compose file (`backend` service): `KUOTAKIT_APP_KEYS`, `KUOTAKIT_SECRET_KEYS`, `KUOTAKIT_ENTITLEMENTS_FILE` (a file in the shared volume `/fixtures`, which the fake Google writes when it starts), `KUOTAKIT_GOOGLE_PACKAGE_NAME`, `KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE` (also in `/fixtures`; its `token_uri` is the fake Google), `KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE` and `KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL`.
- **Request limits that can be set low, and a way to take the client address from `X-Forwarded-For`** (ADR 0016): the suite plays the reverse proxy and gives every scenario an address of its own, so that one scenario never spends the budget of another. The compose file sets `KUOTAKIT_RATE_LIMIT_PER_MINUTE=60`, the two limits of receipts and `KUOTAKIT_TRUST_FORWARDED_FOR=true`. L1-09, L1-10, L1-11 and L1-12 send up to 1000 calls from one address and fail if no `429` arrives. L1-10 also requires that a flood of wrong keys is limited at all, and then that a good key from the same address still gets through.
- For Level 2: Google's addresses configurable (`KUOTAKIT_GOOGLE_API_BASE_URL`, `KUOTAKIT_GOOGLE_CERTS_URL`) behind a lock (`KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS=true`) that is never on in production (ADR 0018).
- The entitlements of [`conformance/`](../conformance) are a fixed file: `premium` (two subscriptions) and `lifetime` (one non-consumable product), one offering `default` with three packages. Your backend must be able to read the `entitlements.yaml` format of the reference backend or an equivalent.

## Manifest
Every scenario has an ID that is part of its name in the report. `conformance/manifest.txt` is the canonical list; a test fails if this table, the manifest and the scenarios differ. Removing an ID needs a change of ADR 0007. Scenarios assert only what a client can observe: the answer, the customer info that `GET /v1/subscribers/{id}` then returns and, from Level 2, the log of what the fake Google was asked.

| ID | What it checks |
|---|---|
| L1-01 | health needs no key |
| L1-02 | a missing, wrong or malformed key is a 401 with the error body, on every authenticated endpoint |
| L1-03 | an app key and a secret key both read |
| L1-04 | the offerings equal the fixture entitlements |
| L1-05 | an unknown user gets an empty customer info |
| L1-06 | invalid app user ids are a 400 |
| L1-07 | a body over 16 KiB and malformed bodies are a 400 |
| L1-08 | an unknown path or method is a plain 404 or 405 |
| L1-09 | over the request limit the answer is a 429 with Retry-After and the code rate_limited |
| L1-10 | a flood of wrong keys does not use up the budget of good keys |
| L1-11 | every response that docs/api/openapi.json describes matches it |
| L1-12 | the codes of the error bodies are the ones of the table Errors in docs/api-spec.md |
| R-01 | an App Store receipt is a 400 until Phase 2 (reference backend only) |

`R-01` is asked only of the reference backend (`CONFORMANCE_REFERENCE=true`, set by the reference compose file): it describes a temporary behaviour that Phase 2 removes, and other backends are not required to have it.

## Plugging in a backend
1. Build your backend as an image that reads the settings above.
2. Run the command of "Running it against your backend". Read the report: each `FAIL` has the ID and the reason (what was expected and what came back).
3. Repeat until `contract conformant (Level 1)`; then make Google's addresses configurable and ask for all levels.
4. A run can be repeated against a backend that stays up: every call comes from a random address and a random user, so runs do not share request budgets (a clash is possible only by chance, about one in 65 000 per scenario).

## Checking the suite itself
A suite that passes a broken backend proves nothing, so the suite checks itself. `self-check` puts a proxy between the suite and the backend that corrupts the answers (a 401 turned into 200, an entitlement granted to a user who bought nothing, a required field removed, the `Retry-After` header dropped, a 429 turned into 200) and requires the scenario meant to notice each corruption to fail:
```bash
CONFORMANCE_COMMAND=self-check docker compose -f docker-compose.conformance.yml \
  -f docker-compose.conformance.reference.yml up --build --abort-on-container-exit --exit-code-from conformance
```
CI runs both commands, the Level 1 only variant, and a check that an incomplete backend (one that only answers the health check) fails naming the scenarios.

## Limits
- The fake Google is not Google: it answers what the documentation says it answers, as far as the reference backend has been checked against it. Validating against the real Google is a separate step (a test track of Google Play).
- The control API of the fake has no authentication, on purpose: it lives in a closed network, and the backend under test could script it. Never expose it.
- The fixtures (the key of the fake service account, the test API keys) open nothing outside the closed network. The key pair is made when the fake starts and written readable by every user of the shared volume (the backend runs as another user); nothing secret is stored in the repository.
