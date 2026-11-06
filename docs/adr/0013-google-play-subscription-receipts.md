# ADR 0013 — Google Play subscription receipts

**Status:** Accepted · **Date:** 2026-11-04

## Context
`POST /v1/subscribers/{id}/receipts` must turn a Play purchase token into a stored purchase without ever granting access on a doubt (ADR 0003). Official sources: [`purchases.subscriptionsv2.get`](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get) is the source of truth for a subscription; acknowledging is only in the v1 `purchases.subscriptions.acknowledge`; a purchase not acknowledged in three days is refunded by Google.

## Decision
- **State.** Only `ACTIVE`, `CANCELED` (cancelled but paid until its end) and `IN_GRACE_PERIOD` grant access, and only while the line item's `expiryTime` is in the future. `ON_HOLD` and `PAUSED` are stored (their own statuses, migration V2) and never grant. `PENDING`, `PENDING_PURCHASE_CANCELED` and any state this backend does not know are refused (`402`), not guessed. An access-granting state with no readable end date is refused too: it would never expire.
- **Trial and introductory price** come from `offerPhase`; the billing problem date is kept from the first time it is seen until it clears.
- **Order: ask Google, check, acknowledge, save.** Nothing is saved unless Google confirmed the purchase and, if needed, the acknowledgement worked. A failure leaves no trace and the app retries. A purchase that grants no access (expired, on hold) is saved but never acknowledged.
- **Ownership.** A token already stored for another user is `409`. *Superseded by ADR 0017: the purchase must now carry the account id of its buyer, and one without it is refused.* In this version, if the app attached an `obfuscatedExternalAccountId` only that user could claim it; if it did not, the first user to send a valid token got it: the token is a secret of the buyer's device, like the app user id (ADR 0009 notes). Apps should set the account id when they build the billing flow (the SDK will).
- **Cheap checks first.** Package name, and length and emptiness of product and token, are checked before Google is called.
- **Errors.** Google answering 400, 404 or 410 means "not a purchase" → `402 purchase_invalid`. Anything else (401, 403, 429, 5xx, network) → `502 store_unavailable`. Any failure of the *acknowledge* call is `502` too, even a 400: the purchase was just read successfully, so it is our problem or a race between two requests acknowledging at once, and the retry sees it acknowledged. This mapping is **our inference**: the official reference does not list which statuses an unknown token gets. It is tested against a simulated transport, not against Google. Error messages never contain the token, and neither do the causes: the exception from Google carries the request URL (which holds the token), so it is replaced by one with only the HTTP status and Google's `reason`.
- **Code.** The official generated client (`google-api-services-androidpublisher`) lives only in `backend.google`, behind the `PlayStoreApi` port of the domain; a Konsist rule enforces it. The domain mapping is pure and covered by mutation testing.
- **Config.** `KUOTAKIT_GOOGLE_PACKAGE_NAME` and `KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE`, both or none. Without them the endpoint answers `502` (fail closed).

## Known limits (on purpose, for the next task)
- **No notifications yet.** Until the RTDN webhook exists the backend learns about renewals only when the app submits the receipt again, and about refunds or revocations before the end date not at all: a refunded subscription keeps granting access until its stored expiry. This is the main reason the next task is not optional.
- Only the line item whose product matches the receipt is read; plan changes with several items are not handled. (`linkedPurchaseToken` and one-time products came later: ADR 0015.)
- The receipt body is limited to 16 KiB. There is no rate limit: anyone with the public app key can make the backend call Google for random tokens and spend its quota (part 2b).
- A purchase made by a license tester (`testPurchase`) is treated like a real one. Whether `expiryTime` moves forward during `IN_GRACE_PERIOD` is not confirmed; if Google leaves the old date, a user in grace is stored as expired (fails closed, but wrong for them).
- A buyer whose app changes the app user id after buying cannot claim a purchase that carries the old account id (`409`).
- No retry policy for Google calls; the Google client's defaults and 10 s / 20 s timeouts apply.
- The multi-line-item and error-status behaviour has not met the real Google yet.

## Alternatives considered
- **Our own HTTP calls** (Ktor client + JWT signing for the service account): fewer dependencies, but we would write and maintain the OAuth flow; the official library does it and tracks API changes. The cost is a larger image (Guava, Gson, Apache HTTP), contained by the port.
- **The v1 `purchases.subscriptions.get`**: older and incomplete (no offer phase, no multi-item). Rejected.
- **Saving first, acknowledging later**: simpler for the client but leaves purchases that grant access and may be refunded by Google. Rejected.
