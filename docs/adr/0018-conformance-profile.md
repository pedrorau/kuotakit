# ADR 0018 — The conformance profile: what a backend offers to be tested

**Status:** Accepted · **Date:** 2026-11-11

## Context
The conformance suite (ADR 0007) must test any backend as a black box, in any language, without real store accounts. For the behaviour that depends on the stores, the suite stands a **fake Google** in front of the backend, so the backend must be able to talk to a Google that lives at another address. This ADR defines what a backend has to offer for that: the *conformance profile*. The reference backend implements it (plan `docs/plans/2026-11-conformance-profile.md`); the suite itself comes in the next pull request (`docs/plans/2026-11-conformance-suite.md`).

The profile is deliberately small. A backend that offers it can be tested at Level 2 (the behaviour of the stores); a backend that does not can still be tested at Level 1 (the contract, with no Google) and is then at most "contract conformant" (see ADR 0007).

## Settings
Two settings, with these names in every backend, redirect what the backend asks Google. The paths, bodies and answers stay exactly Google's.
- **`KUOTAKIT_GOOGLE_API_BASE_URL`**: the root address of the Play Developer API, for example `http://fake-google:9000/`. Every call below that goes to `androidpublisher.googleapis.com` goes to this address instead, with the same path.
- **`KUOTAKIT_GOOGLE_CERTS_URL`**: the address the signing keys of the Pub/Sub push tokens are fetched from (see "Signing key formats").
- **The token endpoint needs no setting.** The Google authentication libraries take it from the `token_uri` field of the service account key file, and the suite generates that file with its own address there. A backend whose library does not read `token_uri` has to offer its own way to redirect it.

A value must be an `http` or `https` address with a host; anything else stops the start-up with a message that names the variable.

## Lock
Using either setting also needs **`KUOTAKIT_ALLOW_CUSTOM_GOOGLE_URLS=true`** (it must be exactly `true` or `false`). Without it the backend refuses to start and says why. With it, the backend writes a warning in its log every time it starts (tested in `WiringTest`). The lock exists so that a mistake in production cannot point a backend at a fake Google, and so that the image under test is the shipped one: what differs from production is these settings and the fixtures (including the key file, whose `token_uri` is not behind the lock, see Known limits). The lock is exactly `true` or `false` (surrounding spaces are ignored), even when no address is set. Other backends must keep the same lock.

A setting for an address with no Google settings to use it (no package name and service account file, or no Pub/Sub settings) is an error, not a silent no-op.

## Fixtures
*Draft: the suite pull request generates these and may amend this section.* The suite starts the backend with:
- a service account key file with a throw-away RSA key and `token_uri` pointing at the fake, generated at start into a volume shared with the backend (nothing secret is stored in the repository);
- an `entitlements.yaml` with the products the scenarios use, including a one-time product (`one_time_products`);
- an app API key (`pk_...`) and a secret key (`sk_...`);
- the package name of the app, the audience and the service account email that the push tokens carry.

## Google endpoints
What the backend asks Google, and what the fake must answer. The error handling that the backend must show for each is in ADR 0013 (subscriptions), ADR 0015 (one-time products) and ADR 0014 (the webhook).

| Call | Method and path (after the base address) |
|---|---|
| Read a subscription | `GET /androidpublisher/v3/applications/{package}/purchases/subscriptionsv2/tokens/{token}` |
| Acknowledge a subscription | `POST /androidpublisher/v3/applications/{package}/purchases/subscriptions/{productId}/tokens/{token}:acknowledge` |
| Read a one-time purchase | `GET /androidpublisher/v3/applications/{package}/purchases/productsv2/tokens/{token}` |
| Acknowledge a one-time purchase | `POST /androidpublisher/v3/applications/{package}/purchases/products/{productId}/tokens/{token}:acknowledge` |
| Get an access token | `POST` to the `token_uri` of the key file (the OAuth `jwt-bearer` grant) |
| Get the signing keys | `GET` of `KUOTAKIT_GOOGLE_CERTS_URL` |

The calls to the Play Developer API carry `Authorization: Bearer <the access token the fake issued>`.

## Signing key formats
Google serves the keys that sign Pub/Sub push tokens in two formats, and different libraries read different ones, so the fake serves both, at Google's own paths: **`/oauth2/v1/certs`** (a JSON map from key id to a PEM X.509 certificate) and **`/oauth2/v3/certs`** (a JWKS). A backend points `KUOTAKIT_GOOGLE_CERTS_URL` at the one its library reads. The reference backend reads the PEM map.

## Start-up contract
The suite expects of a backend under test:
- `GET /health` answers `200` within a time the suite waits for;
- the settings above, the lock, and the settings of ADR 0013 to 0016 that the scenarios use (the package name, the service account file, the Pub/Sub audience and email, and the request limits: the rate-limit scenarios need low limits, whose values the suite pull request fixes);
- the fixtures above, mounted where the settings say.

## Known limits
- A fake Google is not the real one: it only shows that the backend follows the documented behaviour of the Google API. The real Google is validated at the end of Phase 1, with an app on an internal test track.
- **The token endpoint is not behind the lock.** It comes from `token_uri` of the service account key file, which the operator controls like the settings. A key file from Google carries Google's address; one made by the suite carries the fake's, and the signed assertion that the backend sends there would travel over plain HTTP if that address is `http`. It is for testing, with a throw-away key.
- Only the Play Store is covered; the App Store (Phase 2) gets its own fake and its own settings.
- The reference backend has not been run against the JWKS format; it reads the PEM map. That the fake serves both is for backends that read the other one.
- The fixture part of this ADR is a draft until the generator exists.

## Alternatives considered
- **A suite that only checks the contract:** simple, but it cannot test that a valid purchase grants access, a refund removes it, or a store failure never grants it, which is what a backend is for. It stays as Level 1.
- **Redirecting the network instead of the backend** (DNS and certificate tricks): needs no setting but is fragile and different in every language and image.
- **A setting that is always allowed:** one more way to point a production backend at a fake server by mistake; the lock costs one variable.
