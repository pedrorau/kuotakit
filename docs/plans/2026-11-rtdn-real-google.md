# Plan: Google's real-time notifications validated against the real Google (5a)

- **Origin:** context (the maintainer's run of 2026-11-13: after a refund, Restore said "0 sent" because Google no longer lists a refunded purchase, so the backend kept the plan active until its stored end date; only notifications can fix that)
- **Approved by the maintainer on:** 2026-11-13 ("lo del tunel creo q va bien.. porque solo son pruebas.. procede")
- **Branch / PR:** `docs/plan-rtdn-real-google` / not yet

## Goal
Run the webhook `POST /v1/webhooks/google` (ADR 0014), which only ever saw test-signed tokens and a fake Google, against the real Pub/Sub and the real Play Console, with the temporary tunnel as its address. Phase 1 exit criterion: a cancel or a refund made in Google shows in the backend **without** the app sending the receipt again.

## Scope
- The maintainer creates in Google Cloud (project of the service account): the Pub/Sub API enabled, a topic, a push service account, a push subscription with authentication and an audience, and in Play Console the real-time notifications with that topic (steps in `docs/sandbox-play-console.md` part I, written for a stable address; here the endpoint is `<tunnel>/v1/webhooks/google`).
- The agent sets `KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE` and `KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL` in the local `.env` (git-ignored), restarts the backend, and tests: the *Send test message* of Play Console, a purchase, a cancel, a refund by the API, and the app's Plan screen.
- What is read in the backend log and the database is written in the session log; what differs from ADR 0014 is fixed in a follow-up (plan updated, not guessed).
- Docs: lessons in `docs/sandbox-play-console.md` part I and `docs/google-play-setup.md`; ADR 0014 gets an amendment "verified with the real Google" limited to what was observed.

## Out of scope
- A stable address and the purge (5b), any code change unless the run shows a bug (then a plan of its own), iOS.

## Acceptance criteria
- [x] The test message of Play Console reaches the webhook through Pub/Sub and is answered `204` — checked by: the backend log and the "Send test message" result
- [x] A cancel made in Play Store shows in the backend as not renewing with the app closed or without Restore — checked by: the `purchases` row (`auto_renewing`) and the Plan screen after a Refresh
- [x] A refund made by the API ends the entitlement in the backend without the app sending anything — checked by: the `purchases` row, `store_events`, and the Plan screen after a Refresh
- [ ] ~~A duplicate push is skipped~~ dropped: not exercised on purpose (Google sent no duplicate that we could tell apart); covered by the existing tests of ADR 0014. The log did show `ALREADY_FINAL` for notifications that arrived after the refund
- [x] Nothing secret is committed or pasted: the audience and the email go only to `.env` — checked by: `git status` before every commit
- [x] An independent read-only review of the docs diff found nothing that blocks — checked by: the review report

## Steps
- [x] The maintainer's console steps (Pub/Sub topic, service account, subscription, Play Console).
- [x] `.env` (environment variables of the shell, the guard does not let the agent edit `.env`), restart, tests above.
- [x] Docs and amendment, review, PR.

## Validation
- The log of the backend and a query of `purchases` and `store_events` after each action.
- Not validated: any stable address, retries after a long outage of the backend.

## State model
None (no code).

## Decisions taken
- **The temporary tunnel is used** (maintainer): only tests; the subscription endpoint must be updated if the tunnel restarts, so the run is done in one session.
- **Cloud billing requirement of Pub/Sub is unverified**; if the console asks for it, the maintainer decides.

## Status
Done
- 2026-11-13: found that a refund is invisible to Restore (Google does not list refunded purchases): this is the reason for the task.
- 2026-11-13: run done on the maintainer's phone: test message `IGNORED` (204), a purchase announced before its receipt arrived (`UNKNOWN_PURCHASE`, then the receipt of the app saved it), a cancel in Play Store reached the backend in about 30 s (`UPDATED`, `auto_renewing=false`) with the app in the background, and a refund by the API reached it in about 1 s (`UPDATED`, status `refunded`; two later `ALREADY_FINAL`); the Plan screen showed "not active" after Refresh, with no Restore. **ADR 0014 holds with the real Google.** Pub/Sub asked for no billing.
