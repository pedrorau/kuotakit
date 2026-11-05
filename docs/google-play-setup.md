# Connect the backend to Google Play

The backend validates every Android purchase with the Google Play Developer API, using a Google Cloud **service account**. Nothing here goes in the app (rule 3 of AGENTS.md): the key stays on your server.

Checked on 2026-11-04 against [Getting started with the Google Play Developer API](https://developers.google.com/android-publisher/getting_started). Steps marked *not in the official page* come from general Google Cloud knowledge and were not confirmed there.

1. **Google Cloud project.** Create one, or use an existing one.
2. **Enable the API.** In the Cloud Console, enable the *Google Play Android Developer API* for that project.
3. **Service account.** Create a service account in the same project.
4. **Key.** Create a JSON key for it and save it as `secrets/play-service-account.json` (*not in the official page*). The folder and the file name are already git-ignored.
5. **Play Console access.** In *Users and permissions*, choose *Invite new users*, enter the service account's email and grant, as the official page says for the billing APIs:
   - *View financial data, orders, and cancellation survey responses*
   - *Manage orders and subscriptions*
6. **Configure the backend.** In `.env`:
   ```
   KUOTAKIT_GOOGLE_PACKAGE_NAME=com.example.app   # the applicationId of your app
   ```
   and start it with the Google file on top of the main one:
   ```
   docker compose -f docker-compose.yml -f docker-compose.google.yml up --build
   ```
   Outside Docker set `KUOTAKIT_GOOGLE_PACKAGE_NAME` and `KUOTAKIT_GOOGLE_SERVICE_ACCOUNT_FILE` (a path). Both or none: with only one the backend refuses to start and says which is missing.

## Notifications (renewals, cancellations, refunds)
Without them the backend only learns about a purchase when the app sends the receipt again, and a refund is not seen (ADR 0014). Official pages, read on 2026-11-05: [RTDN setup](https://developer.android.com/google/play/billing/getting-ready#configure-rtdn) (topic, publisher role, Play Console menu, test message, notification types) and [Pub/Sub push authentication](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions) (token claims, token creator role). What is marked *not in the official pages* comes from general knowledge. The backend must be reachable over HTTPS from the internet.

1. **Topic.** In Pub/Sub create a topic and give `google-play-developer-notifications@system.gserviceaccount.com` the *Pub/Sub Publisher* role on it.
2. **Push service account.** Create a service account just for pushes (whether it needs any role of its own is *not in the official pages*). The Pub/Sub service agent `service-{PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com` needs the *Service Account Token Creator* role on it.
3. **Push subscription.** Create a *push* subscription on the topic, with endpoint `https://YOUR-HOST/v1/webhooks/google`, authentication enabled, that service account, and an **audience** (the page calls it the audience override and does not say what the default is; the backend checks that the token carries exactly the audience you set in `.env`; using the endpoint URL is our suggestion, *not in the official pages*). The details of the console or `gcloud` options are in the official page, not repeated here.
4. **Backend settings** in `.env`: `KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE` (the same audience) and `KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL` (the push service account). Both or none, and only with the Google Play settings above.
5. **Play Console.** In *Monetize > Monetization setup* enable real-time notifications, enter the topic (`projects/{project_id}/topics/{topic}`), choose *subscriptions and all voided purchases*, and use *Send Test Message*: the backend answers 204 and logs it as ignored.

If the token cannot be verified the backend answers `401`; if Google's keys or the Play API cannot be reached it answers `502` and Pub/Sub retries. A notification that can never be handled (another app, a test message, a body that cannot be read) is answered `204` and shows in the log.

## Good to know
- The container runs as an unprivileged user. If the backend says it cannot read the service account, make the file readable by others and keep the folder private, so only your user reaches it on the host: `chmod 700 secrets && chmod 644 secrets/play-service-account.json` (*not in the official page*).
- The official page does not say how long the access takes to apply after the invitation. If Google answers 401 or 403 right after step 5, the backend answers `502 store_unavailable` and grants nothing; try again later.
- The backend asks only about the app in `KUOTAKIT_GOOGLE_PACKAGE_NAME`. A receipt for another package is a `400`.
- Subscriptions only for now. One-time products arrive with a later task. Without the notifications above a refund is not seen (ADR 0013, ADR 0014).
- This has been tested against a simulated Google (the library's own mock transport and test-signed tokens), not against the real one: that needs an app on a Play internal testing track, which comes with the Android SDK and example app.
