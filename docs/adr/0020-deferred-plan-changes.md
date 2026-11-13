# ADR 0020 — Deferred plan changes (`ReplacementMode.DEFERRED`)

**Status:** Accepted · **Date:** 2026-11-12

## Context
A subscriber can change plan from the app. Google's documentation recommends `ReplacementMode.DEFERRED` for "downgrading to a less expensive tier": the user keeps what they paid for and the new plan starts when the old one ends ([Subscriptions: replacement modes and handling a deferred replacement](https://developer.android.com/google/play/billing/subscriptions)). ADR 0015 handled replacements that take effect at once (`linkedPurchaseToken`). A deferred one was refused (`402`, scenario L2-37) and, being refused, never acknowledged: Google refunds a purchase that is not acknowledged within three days (ADR 0013), so a downgrade would have been cancelled silently.

What Google documents: with this mode "the new purchase is issued immediately with two items", the existing one with auto-renew off and its end set to the end of the billing cycle, and the new one which begins after it; a `SUBSCRIPTION_PURCHASED` notification with the **old** product id is sent at once; `subscriptionsv2.get` of the new token returns two line items, the replaced one carrying `deferredItemReplacement.productId`; `latestSuccessfulOrderId` is absent for an item not owned yet; and the first renewal "follows the standard behavior of any other renewal" ([`subscriptionsv2` reference](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2)).

## Decision
- **`PlaySubscriptionItem` gains two facts of Google's answer:** `deferredReplacement`, the product that will replace the item (`deferredItemReplacement.productId`), and `ownedByUser`, false for an item Google gives no `latestSuccessfulOrderId` ("not present if the item is not owned by the user yet"); a purchase with a single item is the purchase itself and counts as owned.
- **The mapper decides in one place which item of a purchase the user has now** (`PlaySubscriptionMapper.currentItem`):
  - While an item that is to be replaced by the product named still lasts (its end is in the future), that old item is the one the user has, even if the receipt names the new product.
  - Once an item has ended and names a replacement that is in the purchase, the replacement is the one the user has, even if a notification still names the old one (a chain of replacements is followed at most as many steps as there are items).
  - Anything else is the item of the product named, and the usual rule applies: an item with no end date is refused (`402`).
  - **An item the user does not own yet is never the current one**, even if it carries an end date, and **anything ambiguous is refused, never chosen by the order of the list** (two items that both match, or two that are the plan that replaces another).
- **The stored purchase is the current item:** its `productId`, `expiresAt` and `autoRenewing` are the old plan's during the deferral (auto-renew off, so `will_renew` is false) and the new plan's once it starts. It is the same row of the token; `save` already updates them. The replaced purchase (`linkedPurchaseToken`) is expired as in ADR 0015: the user does not lose access because the new token carries the old item until its end.
- **A notification after the change took effect** names the stored product. If Google no longer lists that product in the purchase, the one item the user owns is the plan they have now and is used; with none or several owned items nothing changes (`STORE_REJECTED`, as for any purchase Google no longer describes as one we understand).
- **The purchase is acknowledged under the product it has now** (the old plan during the deferral, which is also the product of the notification Google sends), not under the product the receipt names.
- **Everything else is unchanged:** the account id is required (ADR 0017), a refund or revocation is final, an unreadable purchase is refused and nothing is acknowledged.
- **Conformance:** scenarios L2-38 to L2-41 (accepted, takes effect, unreadable is refused, ownership); L2-37 stays and is narrowed to what it always scripted: a new line item with no end date and no old item to follow.

## Consequences
- A downgrade with Google's recommended mode works and the user keeps the paid period.
- **Not verified against the real Google** (it needs an app on a test track, a task of Phase 1): whether the new item has an `expiryTime` before it starts, what the two items look like after the first renewal (does the old one stay, expired, with its replacement?), and the state of the purchase in between. The rules above encode the reading of the documentation, and fail closed: a combination they do not recognise is refused as before, which costs a refused purchase and never access.
- **Unverified, and not caught by the fake Google:** that `subscriptions.acknowledge` accepts the old product id with the new token (the notification Google sends for the new token carries the old product id, which is why that product is used). If Google refused it, the receipt would be a `502`, nothing would be saved, and Google would refund the purchase after three days: no worse than before, but not a working downgrade.
- **Unverified:** what Google reports for the *old* token after a deferred change. ADR 0015 relies on Google showing a replaced token as expired; if it kept reporting it active, a notification for it would re-read it and its row could grant again (two rows of the same product). The old token is expired when the new purchase is saved, as for any replacement.
- Subscriptions with add-ons (`KEEP_EXISTING`, several owned items), `deferredItemRemoval`, prepaid plans and installments are not handled.

## Alternatives considered
- **Keep refusing it:** the change is cancelled by Google after three days; users would see a downgrade undo itself.
- **Store the new product at once with a start date:** the entitlement would be granted before the old plan ends, or would need a "starts later" state; reading the current item keeps one row and one rule.
- **Wait for the renewal notification to create the purchase:** the webhook never creates purchases (ADR 0014), and the token is acknowledged at the time of the change, not at the renewal.

## Amendment (2026-11-12): checked against the real Google
A deferred downgrade (monthly to annual, a test subscription of a license tester on a real phone, the purchase and Google's answers read with the Play Developer API) confirmed the reading of this ADR:
- Google's answer for the new purchase has **two line items**: `premium_annual` with **no `expiryTime`** and `premium_monthly` with its end and `deferredItemReplacement` naming the annual product; the new purchase is `ACTIVE` and has a `linkedPurchaseToken`.
- **`subscriptions.acknowledge` was accepted** with the old product and the new token: the backend acknowledges under the product the purchase has now (the old plan during the deferral), and Google reports the purchase `ACKNOWLEDGED`.
- **The old token is `EXPIRED`**, with its end at the moment of the change, as ADR 0015 relies on.
- The backend stored the new purchase as the old plan (`premium_monthly`, ending at the old end, not renewing) and, after that end, once the receipt was sent again (the app was restarted), as `premium_annual` active.
- **After the old plan ended (second run, a phone driven over adb):** for about 25 seconds Google's answer was still changing and the backend refused a receipt (`refused`, nothing granted: fail closed); a few seconds later Google reported the annual item with its end date and an order, and the **monthly item without `deferredItemReplacement`** (it is dropped once the swap happened), and the backend, sent the receipt again, stored the purchase as `premium_annual`, active, renewing.
- **Still not verified:** the first renewal of the annual plan after the swap, and the behaviour with Google's notifications on (they are not set up yet in the sandbox).
- The call of the SDK for such a change stayed waiting. The likely cause (a hypothesis, not confirmed with a device log) is that the Billing Library lists the replaced product in the new purchase; the SDK now accepts that too (amendment of ADR 0019, subphase 1c).
