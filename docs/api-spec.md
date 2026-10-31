# Backend API spec (v1, draft)

The backend is a reference implementation the developer deploys. All responses are JSON. All timestamps are ISO 8601 UTC.

## Authentication

| Key | Prefix | Where it lives | Can do |
|---|---|---|---|
| App key | `pk_` | Inside the mobile app (public) | Read offerings, read own subscriber, submit purchases |
| Secret key | `sk_` | Developer's servers only | Everything, including admin reads |

Sent as `Authorization: Bearer <key>`. Store webhooks use their own verification (see below).

## Endpoints

### `GET /v1/offerings`
Returns offerings defined in `entitlements.yaml`.
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
Responses: `200` CustomerInfo · `400` invalid payload · `402` store says the purchase is not valid · `409` token already linked to another user · `502` store API unavailable (never grants access on failure).

### `GET /v1/subscribers/{app_user_id}`
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
Google Play Real-time Developer Notifications via Cloud Pub/Sub push. Verify the Pub/Sub OIDC token, then re-fetch the subscription from the Play Developer API (never trust the notification body alone).

### `POST /v1/webhooks/apple`
App Store Server Notifications v2. Verify the `signedPayload` JWS against Apple's root certificate chain, then update state.

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
