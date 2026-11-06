# Backend API spec (v1, draft)

The backend is a reference implementation the developer deploys. All responses are JSON. All timestamps are ISO 8601 UTC with a `Z`, for example `2026-12-01T12:00:00Z`; a backend may add fractional seconds, and clients accept offsets such as `+01:00` when reading.

**The exact shape of every request and response is [`openapi.json`](api/openapi.json)**, generated from the `contracts/` models (ADR 0007, ADR 0012) and checked in CI. This document explains the behavior; the models and the OpenAPI document are the source of truth for field names and types. The OpenAPI document carries an example for each response and request, built from the models themselves.

Conventions of the JSON:
- Field names are `snake_case`.
- A nullable field is **always present**, with `null` when it does not apply (for example `billing_issue_detected_at`). A missing field is an error.
- Clients ignore fields they do not know, so a newer backend can add fields without breaking older SDKs.
- `store` is `play_store` or `app_store`; `period_type` is `normal`, `intro` or `trial`; `error.code` is one of the codes below.
- A client reads a value it does not know (a new store, period type or error code) as `UNKNOWN` instead of failing; backends never send `UNKNOWN`. So adding a value is compatible.

## Authentication

| Key | Prefix | Where it lives | Can do |
|---|---|---|---|
| App key | `pk_` | Inside the mobile app (public) | Read offerings, read a subscriber, submit purchases |
| Secret key | `sk_` | Developer's servers only | The same today; reserved for admin endpoints later |

**The app user id is the secret.** An app key is the same for every user of the app, so the backend cannot tell "whose" user an id is: any valid key can read or write any `app_user_id`. Anonymous ids are long random strings (`$anon_...`) and must never be shown, logged or shared. An `app_user_id` is 1 to 128 letters, digits and `_ . $ -`; anything else is a `400`.

Sent as `Authorization: Bearer <key>`. Store webhooks use their own verification (see below).

## Errors

Every non-2xx response of an endpoint has the same body, and the `code` is stable (react to the code, not to the message). A path or method that is not part of the API gets the web framework's plain `404` or `405`, with no body:

```json
{ "error": { "code": "token_already_linked", "message": "The purchase token belongs to another user." } }
```

| Status | `code` | Meaning |
|---|---|---|
| 400 | `invalid_payload` | The body or a parameter is malformed |
| 401 | `unauthorized` | The API key is missing or wrong |
| 402 | `purchase_invalid` | The store says the purchase is not valid |
| 409 | `token_already_linked` | The purchase token is already linked to another user |
| 429 | `rate_limited` | Too many requests from this client or for this user; wait the `Retry-After` seconds |
| 500 | `internal_error` | Something unexpected went wrong; access is never granted on failure |
| 502 | `store_unavailable` | The store API is unavailable; access is never granted on failure |

## Request limits
Every authenticated call is limited per client address (default 300 a minute); receipts, which make the backend ask the store, also per client address (30) and per app user id (10). Over the limit the answer is `429` with `Retry-After` (seconds) and the code `rate_limited`. `/health` and the webhooks are not limited. The counters live in one process; behind a reverse proxy set `KUOTAKIT_TRUST_FORWARDED_FOR=true`, and only then. Variables: `KUOTAKIT_RATE_LIMIT_PER_MINUTE`, `KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_MINUTE`, `KUOTAKIT_RATE_LIMIT_RECEIPTS_PER_USER_PER_MINUTE` (ADR 0016).

## Endpoints

### `GET /v1/offerings`
Returns offerings defined in `entitlements.yaml`. Responses: `200` · `401` · `429` · `500`.
```json
{
  "current": "default",
  "offerings": [
    { "id": "default",
      "packages": [
        { "id": "monthly", "products": { "play_store": "premium_monthly", "app_store": "com.acme.premium.monthly" } },
        { "id": "annual",  "products": { "play_store": "premium_annual",  "app_store": "com.acme.premium.annual" } }
      ] }
  ]
}
```

### `POST /v1/subscribers/{app_user_id}/receipts`
Called by the SDK after a store purchase. The backend validates with the store, saves the purchase, acknowledges it (Android) and returns the updated customer info.
```json
// Android
{ "store": "play_store", "package_name": "com.acme.app", "product_id": "premium_monthly", "purchase_token": "..." }
// iOS
{ "store": "app_store", "bundle_id": "com.acme.app", "signed_transaction": "<JWS from StoreKit 2>" }
```
Responses: `200` CustomerInfo · `400` invalid payload · `401` · `402` store says the purchase is not valid · `409` token already linked to another user · `429` too many requests · `500` · `502` store API unavailable (never grants access on failure). The `store` field tells the two receipt kinds apart.

Android receipts must carry the account id of the buyer, `playObfuscatedAccountId(app_user_id)` (SHA-256 in lowercase hex), attached by the app when it starts the purchase: without it the answer is `402`, with another user's `409` (ADR 0017). Android receipts (subscriptions, ADR 0013, and non-consumable one-time products, ADR 0015; which one is decided by `one_time_products` in `entitlements.yaml`, not by the app): the `package_name` must be the app the backend serves (`400` otherwise), the purchase is read from Google and acknowledged before it is saved, and the answer is the customer info after saving. `ON_HOLD`/`PAUSED`/expired subscriptions are saved and grant nothing; a payment still pending is a `402`. A backend without Google Play configured answers `502`. iOS receipts answer `400` until Phase 2.

### `GET /v1/subscribers/{app_user_id}`
Any valid key can read any user (see "The app user id is the secret"). A user without purchases gets an empty `CustomerInfo` (no entitlements, no subscriptions), not an error: that is what a new app user looks like. Responses: `200` CustomerInfo · `400` invalid app user id · `401` · `429` · `500`. `non_subscription_purchases` items have `product_id`, `store` and `purchased_at`.
```json
{
  "app_user_id": "$anon_6f1c...",
  "request_date": "2026-11-01T12:00:00Z",
  "entitlements": {
    "premium": {
      "is_active": true,
      "product_id": "premium_monthly",
      "store": "play_store",
      "period_type": "normal",
      "expires_at": "2026-12-01T12:00:00Z",
      "will_renew": true,
      "billing_issue_detected_at": null
    }
  },
  "active_subscriptions": ["premium_monthly"],
  "non_subscription_purchases": []
}
```

### `POST /v1/webhooks/google`
Google Play Real-time Developer Notifications via Cloud Pub/Sub push (ADR 0014). No API key: the proof is the OIDC token of the push subscription (`Authorization: Bearer`), checked for issuer, audience, service account email and signature. A refund or a revocation takes access away straight from the verified message; any other subscription change re-reads the subscription from the Play Developer API, because the notification never carries the state. The webhook never creates a purchase; a refund or a revocation for one that nobody has submitted yet waits up to 60 days and is applied when its receipt arrives (ADR 0016). A refunded or revoked purchase is final: nothing written later (a notification, a receipt sent again, a slow update) brings it back. Responses: `204` handled, or acknowledged because it can never succeed (another app, a test message, a one-time product, an unknown purchase, a body that cannot be read or is over 16 KiB; Pub/Sub would otherwise retry it for days) · `401` token not verified or webhook not configured · `502` Google or its signing keys unreachable (Pub/Sub retries) · `500`. There is no `400`.

### `POST /v1/webhooks/apple`
App Store Server Notifications v2. Verify the `signedPayload` JWS against Apple's root certificate chain, then update state. Responses: `204` processed · `400` · `401` (signature verification failed) · `500` (the store retries). The webhook bodies are defined by the stores; the models for them arrive with the webhook endpoints.

### `GET /health`
`200 {"status":"ok"}`. No auth.

## Configuration (`entitlements.yaml`)
```yaml
entitlements:
  premium:
    products:
      play_store: [premium_monthly, premium_annual]
      app_store: [com.acme.premium.monthly, com.acme.premium.annual]
current_offering: default   # id of the offering returned as "current" by GET /v1/offerings
offerings:                  # keyed by offering id; any id is valid, none is reserved
  default:
    monthly: { play_store: premium_monthly, app_store: com.acme.premium.monthly }
    annual:  { play_store: premium_annual,  app_store: com.acme.premium.annual }
```
Products bought once and kept (such as a lifetime unlock) are listed in the optional `one_time_products`, per store; every other product is a subscription. Each must also grant an entitlement above. Consumables are not supported yet.
```yaml
one_time_products:
  play_store: [lifetime_unlock]
```
Store identifiers are always `play_store` and `app_store`, in the API, the YAML and the SDK.

## Store credentials (env vars)
- Google: service account JSON with the Android Publisher API enabled.
- Apple: App Store Server API key (`.p8`), key ID, issuer ID, bundle ID, environment (sandbox/production).

## Data model (first cut)
- `subscribers` (app_user_id, created_at)
- `purchases` (store, product_id, purchase_token / original_transaction_id, status, expires_at, auto_renewing, raw_payload, updated_at)
- `events` (store notifications received, for audit and replay)

## Decisions (closed questions)
- **No outbound webhooks in v0.** The developer's own systems read state with `GET /v1/subscribers/{id}` (secret key). Outbound webhooks are in the roadmap's Later section (ADR 0005).
- **Postgres only in v0,** behind a storage interface so other databases can be added later (ADR 0009).
