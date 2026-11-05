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
| App key | `pk_` | Inside the mobile app (public) | Read offerings, read own subscriber, submit purchases |
| Secret key | `sk_` | Developer's servers only | Everything, including admin reads |

Sent as `Authorization: Bearer <key>`. Store webhooks use their own verification (see below).

## Errors

Every non-2xx response has the same body, and the `code` is stable (react to the code, not to the message):

```json
{ "error": { "code": "token_already_linked", "message": "The purchase token belongs to another user." } }
```

| Status | `code` | Meaning |
|---|---|---|
| 400 | `invalid_payload` | The body or a parameter is malformed |
| 401 | `unauthorized` | The API key is missing or wrong |
| 402 | `purchase_invalid` | The store says the purchase is not valid |
| 403 | `forbidden` | The key is valid but may not do this (an app key acting on another user) |
| 404 | `not_found` | The subscriber does not exist |
| 409 | `token_already_linked` | The purchase token is already linked to another user |
| 502 | `store_unavailable` | The store API is unavailable; access is never granted on failure |

## Endpoints

### `GET /v1/offerings`
Returns offerings defined in `entitlements.yaml`. Responses: `200` · `401`.
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
Responses: `200` CustomerInfo · `400` invalid payload · `401` · `402` store says the purchase is not valid · `403` an app key may only submit purchases for its own user · `409` token already linked to another user · `502` store API unavailable (never grants access on failure). The `store` field tells the two receipt kinds apart.

### `GET /v1/subscribers/{app_user_id}`
An app key can read its own user; a secret key can read any user. Responses: `200` CustomerInfo · `401` · `403` · `404`. `non_subscription_purchases` items have `product_id`, `store` and `purchased_at`.
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
Google Play Real-time Developer Notifications via Cloud Pub/Sub push. Verify the Pub/Sub OIDC token, then re-fetch the subscription from the Play Developer API (never trust the notification body alone). Responses: `204` processed · `400` · `401` (token verification failed).

### `POST /v1/webhooks/apple`
App Store Server Notifications v2. Verify the `signedPayload` JWS against Apple's root certificate chain, then update state. Responses: `204` processed · `400` · `401` (signature verification failed). The webhook bodies are defined by the stores; the models for them arrive with the webhook endpoints.

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
