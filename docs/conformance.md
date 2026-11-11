# Conformance suite

The conformance suite is a program that talks to the address of **any** KuotaKit backend and checks what it answers, not how it is written (ADR 0007). The reference backend (Kotlin) passes it in CI as a Docker image; a backend written in another language is a valid KuotaKit backend when it passes it. It needs no Kotlin and no Gradle from you: it is a Docker image.

## Levels
- **Level 1, the contract.** What every backend does with no store involved: API keys, the error body of every status, request limits, offerings, an empty customer info, validation, and every answer matching [`api/openapi.json`](api/openapi.json). A backend that passes it is **contract conformant (Level 1)**.
- **Level 2, the behaviour of the stores:** receipts, refunds, the webhook and the failures of Google, against a fake Google that the suite scripts. A backend that passes both levels is **fully conformant**. The maintainer accepts a second backend as an official one only if it is fully conformant (ADR 0007).

A backend that cannot send its Google calls to another address can only be contract conformant: Level 1 never makes the backend ask Google (the compose file still starts the fake Google and gives the backend the Google settings, which it may ignore).

## Running it against the reference backend
```bash
./gradlew :backend:installDist :conformance:installDist
docker compose -f docker-compose.conformance.yml -f docker-compose.conformance.reference.yml \
  up --build --abort-on-container-exit --exit-code-from conformance
```
It exits `0` and ends with `Result: fully conformant`. Any failure exits `1` and names the scenarios: `Result: NOT conformant: failed L1-02, L1-09`.

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
- For Level 2, what the scenarios assume of your backend (all follow ADR 0013 to 0018 and the reference backend): it asks Google at the paths of the table of ADR 0018 (L2-01 and L2-11 check the acknowledge call by its path); it does not keep Google's signing keys for longer than the fake allows (the fake sends no `Cache-Control`, so a backend that caches the keys anyway would answer a push from its cache and fail L2-33, which makes the keys unreachable and expects a 502); and it makes no call to Google that the scenario does not script, apart from the token request, which it may cache.
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
| L2-01 | a valid subscription grants its entitlement and the fake Google's log shows one acknowledge |
| L2-02 | a free trial and an introductory price show in the period type |
| L2-03 | an expired subscription, and one past its end date though Google still says active, deny |
| L2-04 | a subscription in its grace period grants and dates the billing problem |
| L2-05 | on hold and paused subscriptions deny, and when Google later reports one active again and the receipt is sent again, access returns |
| L2-06 | a subscription cancelled until its end grants and does not renew |
| L2-07 | pending and unknown subscription states are a 402, the customer info stays empty and the log shows no acknowledge |
| L2-08 | a receipt for a product that the purchase does not match is a 402 |
| L2-09 | a subscription that Google reports as already acknowledged is not acknowledged again |
| L2-10 | a failing acknowledgement is a 502 and the customer info stays empty; once it stops failing the same receipt is a 200 with one acknowledge in the log |
| L2-11 | a valid one-time purchase grants with no end date and is listed as a non-subscription purchase |
| L2-12 | a refunded one-time purchase (nothing left to refund) denies |
| L2-13 | a one-time purchase with no refundable quantity is a 402 |
| L2-14 | pending and cancelled one-time purchases are a 402 |
| L2-15 | a subscription without the account id is a 402, the customer info stays empty and the log shows no acknowledge |
| L2-16 | a subscription with another user's account id is a 409 |
| L2-17 | an account id that is the plain app user id, another case of the hash or the hash with added spaces is a 409 |
| L2-18 | one token claimed by two users: the second is a 409 and the first still has the access |
| L2-19 | an upgrade ends the old plan, and a refused new purchase leaves the old plan alone |
| L2-20 | a 400, 404 or 410 from Google when reading is a 402 |
| L2-21 | a 401, 403, 429, 500 or 503 from Google when reading is a 502 and grants nothing |
| L2-22 | a refused connection to Google is a 502 |
| L2-23 | a receipt for another package is a 400 and the log shows that Google was not asked |
| L2-24 | a push without proof, or with a wrong audience, account, unverified email, issuer, expired, badly signed or alg none token, is a 401 and the customer info does not change |
| L2-25 | a test message is a 204 |
| L2-26 | a refund removes access for good and a receipt sent again does not restore it |
| L2-27 | a revocation removes access for good as a refund does |
| L2-28 | a refund before the receipt: the receipt that comes later is answered with no access, and the log shows no acknowledge |
| L2-29 | a duplicate message is handled once (the log shows one read) |
| L2-30 | a notification of another app is a 204 and the log shows that Google was not asked |
| L2-31 | a renewal is read again and extends the access |
| L2-32 | one-time purchase cancellation and refund notifications remove access |
| L2-33 | an unreadable body is a 204, and unreachable signing keys are a 502 |
| L2-34 | a notification for a purchase nobody submitted is a 204, the customer info stays empty, and a receipt that comes later behaves as a new one |
| L2-35 | a one-time purchase without the account id is a 402 |
| L2-36 | a one-time purchase with another user's account id is a 409 |
| L2-37 | a deferred replacement (the new line item has no end date) is a 402 and leaves the old plan alone |

How the scenarios of Level 2 are grouped: L2-01 to L2-10 subscriptions (states, trial and introductory prices, grace, hold, pause, cancellation, acknowledgement); L2-11 to L2-14, L2-35 and L2-36 one-time purchases; L2-15 to L2-18 whose purchase it is (the account id, ADR 0017); L2-19 and L2-37 what replaces what; L2-20 to L2-23 what Google can say or do wrong, always failing closed; L2-24 to L2-34 the webhook (who may push, refunds, revocations, duplicates, another app, renewals).

`R-01` is asked only of the reference backend (`CONFORMANCE_REFERENCE=true`, set by the reference compose file): it describes a temporary behaviour that Phase 2 removes, and other backends are not required to have it.

## The fake Google
It is the part of the suite that your backend talks to, so you can read what it does. It runs in the `fake-google` service (image of the suite, command `fake-google`) on two ports:
- **Port 9000, Google.** `POST /token` (the OAuth token endpoint: it issues a token and accepts any assertion), the four calls of the Play Developer API of ADR 0018 (they need the token it issued: otherwise a `401` in Google's error format), and the signing keys at `/oauth2/v1/certs` (PEM map) and `/oauth2/v3/certs` (JWKS). A Play call that nobody scripted gets a `404`, as a purchase that Google does not know; an acknowledge succeeds.
- **Port 9001, control.** It is only for the suite, has no authentication and must never be exposed: `POST /control/answer` (`{"method","path","status","body"}`: what that call answers from now on, over everything else), `GET /control/calls` (what the backend asked, in order), `POST /control/reset`, `POST /control/push-token` (a token for the webhook, signed with the fake's key; see `PushSigner` for what can be made wrong: audience, account, unverified email, issuer, expiry, algorithm `none`, a bad signature), and `POST /control/google-down` and `/control/google-up` (port 9000 stops listening, so the connection is really refused).

Your backend never calls the control API. A scenario scripts the answers, makes the app send a receipt or Pub/Sub push, and then checks the answer, the customer info and the log of the fake.

## Reading a failure
`FAIL  L2-26: access after a refund: expected <false> but was <true>` says which scenario, what was checked and what came back. The text of a scenario is in `conformance/src/main/kotlin/.../Level1*.kt` and `Level2*.kt`: one small function per ID, listed at the end of its file with the ID. If a failure says that an answer does not match `docs/api/openapi.json`, the answer has the right status but the wrong shape. Fixing a backend never needs the control API or the fake: they only set the scene.

## Plugging in a backend
1. Build your backend as an image that reads the settings above.
2. Run the command of "Running it against your backend". Read the report: each `FAIL` has the ID and the reason (what was expected and what came back).
3. Repeat until `contract conformant (Level 1)`; then make Google's addresses configurable and ask for all levels.
4. A run can be repeated against a backend that stays up: every call comes from a random address and a random user, so runs do not share request budgets (a clash is possible only by chance, about one in 65 000 per scenario).

## Checking the suite itself
A suite that passes a broken backend proves nothing, so the suite checks itself. `self-check` puts a proxy between the suite and the backend that corrupts the answers and requires the scenario meant to notice each corruption to fail. Level 1: a 401 turned into 200, an entitlement granted to a user who bought nothing, a required field removed, the `Retry-After` header dropped, a 429 turned into 200. Level 2: a 402, a 409 or a 502 turned into 200, every entitlement reported as active, and a push with no valid proof accepted.
```bash
CONFORMANCE_COMMAND=self-check docker compose -f docker-compose.conformance.yml \
  -f docker-compose.conformance.reference.yml up --build --abort-on-container-exit --exit-code-from conformance
```
CI runs both commands, the Level 1 only variant, and a check that an incomplete backend (one that only answers the health check) fails naming the scenarios.

## Limits
- The fake Google is not Google: it answers what the documentation says it answers, as far as the reference backend has been checked against it. Validating against the real Google is a separate step (a test track of Google Play).
- The control API of the fake has no authentication, on purpose: it lives in a closed network, and the backend under test could script it. Never expose it.
- The fixtures (the key of the fake service account, the test API keys) open nothing outside the closed network. The key pair is made when the fake starts and written readable by every user of the shared volume (the backend runs as another user); nothing secret is stored in the repository.
