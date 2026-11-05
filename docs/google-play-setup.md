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

## Good to know
- The container runs as an unprivileged user. If the backend says it cannot read the service account, make the file readable by others and keep the folder private, so only your user reaches it on the host: `chmod 700 secrets && chmod 644 secrets/play-service-account.json` (*not in the official page*).
- The official page does not say how long the access takes to apply after the invitation. If Google answers 401 or 403 right after step 5, the backend answers `502 store_unavailable` and grants nothing; try again later.
- The backend asks only about the app in `KUOTAKIT_GOOGLE_PACKAGE_NAME`. A receipt for another package is a `400`.
- Subscriptions only for now. One-time products and the real-time notifications (renewals, cancellations and refunds that happen while the app is closed) arrive with the next task; see ADR 0013 for what that means until then.
- This has been tested against a simulated Google (the library's own mock transport), not against the real one: that needs an app on a Play internal testing track, which comes with the Android SDK and example app.
