# Set up the sandbox: Play Console, service account and backend

This is the checklist of everything the maintainer has to create outside the repository so that the example app (`dev.pedrorau.kuotakit.sandbox`, plan `docs/plans/2026-11-example-app.md`) can make a real test purchase and the backend can verify it with the real Google. Every value you have to choose already has a default. **Parts B, C and G can be done right now; parts D, E and F wait for the example app** (they need a signed bundle that does not exist until its plan is approved and implemented), and H and I can be done in parallel once G is done. The agent will tell you when the bundle is ready.

Rules for the sources (docs/store-docs-rules.md): what comes from an official page has its link; what is marked *not in the official pages* is general knowledge, was not confirmed there and is the first place to look if a step does not behave as written. Official pages were read on 2026-11-11.

## What you will have at the end
| Thing | Where it lives | Who makes it |
|---|---|---|
| An app "KuotaKit Sandbox" with package `dev.pedrorau.kuotakit.sandbox` | Play Console | you (part B) |
| An upload key (a `.jks` file): **the one you already use in CallFilter and Pique** | your computer, **never in the repository** | you (part C, just copy it) |
| A signed app bundle (`.aab`) on the *internal testing* track | Play Console | the agent builds it, you upload it (part D) |
| Two subscriptions, `premium_monthly` and `premium_annual` (and optionally one one-time product) | Play Console | you (part E) |
| A license tester account and a test device | Play Console, your phone | you (part F) |
| A service account and its JSON key | Google Cloud, Play Console; the file stays on the backend's host | you (part G) |
| A backend with an `https` address | your computer for the first tests, a small server afterwards | you, with the commands of part H |
| Real-time notifications from Google to the backend | Google Cloud Pub/Sub, Play Console | you (part I) |

Order matters (Play Console needs an uploaded app before it lets you create products), so follow the parts in this order:

1. [Decisions](#decisions-already-taken-change-them-only-if-you-must)
2. [A. What you need before starting](#a-before-you-start)
3. [B. Create the app](#b-create-the-app)
4. [C. Create the upload key](#c-create-the-upload-key)
5. [D. Upload the first build to the internal testing track](#d-upload-the-first-build-to-the-internal-testing-track)
6. [E. Create the products](#e-create-the-products)
7. [F. License testers and the test device](#f-license-testers-and-the-test-device)
8. [G. Service account for the backend](#g-service-account-for-the-backend)
9. [H. The backend](#h-the-backend)
10. [I. Real-time notifications](#i-real-time-notifications-after-the-backend-has-a-stable-https-address)
11. [J. What to tell the agent](#j-what-to-tell-the-agent-and-what-never-to-share)
12. [If something goes wrong](#if-something-goes-wrong)

## Decisions already taken (change them only if you must)
| What | Value | Why |
|---|---|---|
| App name | `KuotaKit Sandbox` | what the testers see |
| Package name (application id) | `dev.pedrorau.kuotakit.sandbox` | decided with the maintainer; **permanent**: a package name cannot be deleted or reused ([Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152)) |
| Subscription 1 | product ID `premium_monthly`, name `Premium monthly`, one auto-renewing base plan, ID `monthly`, billing period monthly | the same ids as `entitlements.example.yaml` and the conformance suite |
| Subscription 2 | product ID `premium_annual`, name `Premium annual`, one auto-renewing base plan, ID `annual`, billing period yearly | needed to test a plan change (upgrade and downgrade) |
| One-time product (optional) | product ID `lifetime_unlock`, non-consumable | only if you want to test one-time purchases (ADR 0015); skip it the first time |
| Prices | anything, for example 1.99 and 14.99 in your currency | testers never pay; they use test payment methods |
| Access granted | one entitlement, `premium`, by either subscription (and by `lifetime_unlock` if you create it) | it is in the `entitlements.yaml` of part H |

Two subscriptions, each with its own base plan, and not one subscription with two base plans: the backend tells products apart by the **product ID** of the subscription, and a plan change between the two is then a change between two products (docs/adr/0013, 0015, 0020).

## A. Before you start
- A Google Play developer account (you have one) and a **payments profile linked to it**, which Google requires to sell in-app products ([Set up Play Billing](https://developer.android.com/google/play/billing/getting-ready#set-up-a-google-play-developer-account), [link your payments profile](https://support.google.com/googleplay/android-developer/answer/3092739)). Check it in Play Console: if *Monetize with Play* is there and lets you open *Products*, it is linked. *(Lived in the first run: with no payments profile the menu does not offer products; after creating the profile, and after a first build with the billing library was published, the submenu took a while to appear and *Monetize with Play → Get started* looped for some minutes. Waiting and reloading was the fix.)*
- A computer with this repository, JDK (Gradle brings its own), Docker (Colima works) and an **Android phone with the Play Store** (an emulator image *with Google Play* also works; not tried).
- Know **which email owns the developer account**: license testers and testers are separate from it, and the phone's own Gmail may be a different address (in the first run the owner was not the phone's account, so the phone was not a tester until it was added to the lists of parts D and F).
- A second Google account (a Gmail made only for testing) for the license tester. Do not buy anything with real payment methods on it ([Test your integration](https://developer.android.com/google/play/billing/test): "Gmail accounts created for testing purposes shouldn't be used to make purchases using real instruments on Google Play").
- Your account may be a *personal* account created after 2023-11-13: then Google requires extra testing before an app can go to production (the official page links to ["App testing requirements for new personal developer accounts"](https://support.google.com/googleplay/android-developer/answer/14151465)). It does **not** affect the internal testing track used here; it matters only when the sandbox app is opened to the public.

## B. Create the app
([Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152))
1. Play Console → **Create app**.
2. App name `KuotaKit Sandbox`; default language of your choice; **App** (not game); **Free**.
3. Package name `dev.pedrorau.kuotakit.sandbox`. The official page lists it among the fields of this form; if yours does not ask for it, Play Console takes it from the first bundle you upload (part D), and it must be that one.
4. A contact email for users (an address you are happy to show; a new alias is better than your personal one).
5. Accept the declarations and the Play App Signing terms. **Create app**.
6. Open the dashboard: Google lists tasks to do before publishing (store listing, content rating, data safety…). For the *internal testing* track you only need what Play Console marks as required for it; it tells you.

## C. The upload key (reuse the one you already have)
You already have an upload key, and CallFilter and Pique use the same one (`androidApp/upload-keystore.jks`, alias `upload`; both copies have the same checksum). The example app will sign the same way, so **there is nothing to create**:
1. Copy that file to `examples/android/upload-keystore.jks` of your clone of this repository (git-ignores `*.jks`).
2. Copy `keystore.properties` of Pique to the root of your clone, with the same four keys (`storeFile=examples/android/upload-keystore.jks`, `storePassword`, `keyAlias=upload`, `keyPassword`). `keystore.properties` will be git-ignored here as in your other projects. The agent never needs to read the passwords, and you never paste them anywhere.
3. One thing to know: with one upload key for several apps, if it ever leaks, you have to ask Google to reset the upload key of **each** app. Google keeps the real signing key of every app (Play App Signing), so users are not affected. Using one upload key for several apps is allowed, *but I did not find it stated in the official pages I read*; your two published apps already do it.

Play App Signing is the default: Google keeps the key that signs what users install, and you keep only an **upload key** that proves the bundle is yours ([Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152): "When you create your first release, you can configure Play App Signing").

**Only if you prefer a separate key for the sandbox**, create one outside the repository (*the command is not in the official page; `keytool` comes with the JDK*):
```bash
mkdir -p ~/kuotakit-sandbox-keys && cd ~/kuotakit-sandbox-keys
keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```
It asks for two passwords and your name and country; keep them in your password manager and **back the file up**. Then point `keystore.properties` to it.

In both cases never put the file or the passwords in the repository, in a chat or in a screenshot. The repository already ignores `*.jks` and `*.keystore`, and the agent's guards stop it from editing them; `keystore.properties` is added to `.gitignore` by the example app task.

## D. Upload the first build to the internal testing track
Play Console enables the billing features only after "a version of your app that includes the Google Play Billing Library" has been published, "to any track, including the internal test track" ([Set up Play Billing](https://developer.android.com/google/play/billing/getting-ready#enable-billing-related-features-in-the-google-play-console)). So the first build comes **before** the products.

1. **The agent builds the bundle** once the example app exists (task `docs/plans/2026-11-example-app.md`): `./gradlew :examples:android:bundleRelease`. The app's `minSdk` must be **24 or higher** or Play Console refuses the bundle when automatic protection is on (the example app uses 24). It needs your upload key; the command and the `local.properties` lines will be in the example app's README.
2. In Play Console, open your app → **Test and release → Testing → Internal testing** ([Set up an open, closed, or internal test](https://support.google.com/googleplay/android-developer/answer/3131213)).
*(Button names in Play Console change over time; follow what your screen shows if it differs from the names below.)*
3. **Testers** tab → **Create email list** → a name (`sandbox-testers`) and the emails of the people who will install the app (your test Gmail, your own account, friends): up to 100 testers per app. **Save changes**, select the list, put a feedback email or URL, **Save changes**.
4. **Create new release** → upload the `.aab` → give it a name → **Save**, **Review release**, **Start rollout to Internal testing**. If Play Console asks you to configure Play App Signing, accept Google-managed signing.
5. Copy the **opt-in link** (*Testers* tab). It appears only when the app status is *Published*; a draft app has none. Each tester must open the link **on the test device, logged in with the test account**, and accept.
6. Wait. For a first upload the official page says: "After publishing an open, closed, or internal test for the first time, the test link can take several hours to become available to testers." Later bundles are available "within minutes".

Limits of this track: draft apps and internal test releases are subject to **spend limits** per user per day (daily transactions, per-order value, daily total) ([Test your integration](https://developer.android.com/google/play/billing/test#spend-limits)). The page does not say whether license testers are exempt.

## E. Create the products
Prerequisite: part D done (a bundle with the billing library is published to a track).

### Subscription `premium_monthly`
([Create a subscription](https://support.google.com/googleplay/android-developer/answer/140504))
*(Menu and button names are those of the official page; the defaults for the grace period and the account hold and the availability screen are as Play Console shows them today and may differ on your screen.)*
1. Play Console → your app → **Monetize with Play → Products → Subscriptions → Create subscription**.
2. **Product ID** `premium_monthly` (it starts with a number or a lowercase letter, may have underscores and periods, up to 40 characters; "can't be changed or reused after they've been created"). **Name** `Premium monthly` (up to 55 characters). **Create**.
3. Optionally add benefits and a description. **Save changes**.
4. **Add base plan**: Base plan ID `monthly` (lowercase letters, numbers and hyphens), **Auto-renewing**, billing period **Monthly**. Leave the grace period and the account hold as Google proposes (a license tester sees them shortened: grace period 5 minutes and account hold 10 minutes, [Test your integration](https://developer.android.com/google/play/billing/test#renewals)).
5. **Manage country/region availability** → pick at least the country of your test account. Set a price for that country. **Save**, then **Activate**.

### Subscription `premium_annual`
The same, with Product ID `premium_annual`, name `Premium annual`, base plan ID `annual`, billing period **Yearly**, another price. Activate it too.

The official page says: "For the subscription to be available to Google Play users, you need to create and activate at least one base plan." A subscription that is not active cannot be tested ([Test in-app billing with application licensing](https://support.google.com/googleplay/android-developer/answer/6062777): "One-time products and subscriptions must be published before they can be tested").

### Optional: one-time product `lifetime_unlock`
Create a **managed product** (in-app product) with ID `lifetime_unlock`, following [Create a managed product](https://support.google.com/googleplay/android-developer/answer/1153481) (the steps are in that article; Play Console calls one-time products "managed products"). Activate it. It is a non-consumable: KuotaKit supports those only (ADR 0015).

## F. License testers and the test device
([Test in-app billing with application licensing](https://support.google.com/googleplay/android-developer/answer/6062777))
1. Play Console (account level, not inside the app) → **Manage account → Play apps settings → Monetization settings → License testing** (*the page gives this path; Play Console menus move now and then, so use the search box of Play Console if it is not there*).
2. **Email lists** → **Create list** → a name (`sandbox-license-testers`) and the emails: your test Gmail, and any friend who will try the app. **Save changes**. "Your own publishing account is always considered a licensed tester."
3. License testers are **separate from the testers of the internal track**: the test Gmail must be in **both** lists (part D step 3 and this one). The official page treats them as separate settings.
4. On the test phone: log in to the Play Store **with the test account** and make sure it is the account that installs the app ("If the device has more than one account, the purchase is made with the account that downloaded the app", [Test your integration](https://developer.android.com/google/play/billing/test#license-testers)). Open the opt-in link of part D and install the app from Play.
5. When you buy, the purchase screen shows a notice that it is a test purchase and offers **test payment methods**: *always approves*, *always declines*, *slow test card that approves after a few minutes*, *slow test card that declines after a few minutes* and *approves then charges back*.

What a license tester sees that a real user does not ([Test your integration](https://developer.android.com/google/play/billing/test#renewals)):

| Real period | Test period |
|---|---|
| Subscription of 1 month | renews every 5 minutes |
| Subscription of 1 year | renews every 30 minutes |
| Free trial | 3 minutes |
| Grace period | 5 minutes |
| Account hold | 10 minutes |
| Purchase acknowledgement | 5 minutes (see below) |

- A test subscription renews at most **6 times**, then ends: do not panic when the access disappears after ~30 minutes.
- "For purchases from license testers, a purchase is refunded after 3 minutes if your app does not acknowledge the purchase, and you receive an email about the cancellation." In KuotaKit the **backend** acknowledges, after it verified the purchase: if you see the purchase refunded after 3 minutes, the backend did not receive or did not accept the receipt.
- To buy the same one-time product again, refund and revoke the previous purchase in Play Console (**Orders**; [Refund and revoke purchases](https://support.google.com/googleplay/android-developer/answer/2741495)).
- **Play Billing Lab**, a free app from Google, speeds up transitions (renewal, grace period, account hold), tests trials repeatedly and changes prices for a tester ([Test your integration](https://developer.android.com/google/play/billing/test#play-billing-lab)). You need the same account in the Play Store and in Play Billing Lab.

## G. Service account for the backend
The backend asks Google about every purchase with a **service account**; its key stays on the backend's host and nowhere else (rule 3 of AGENTS.md). Official page: [Getting started with the Google Play Developer API](https://developers.google.com/android-publisher/getting_started).
1. **Google Cloud project**: <https://console.cloud.google.com> → create a project (for example `kuotakit-sandbox`). Skip if you have one.
2. **Enable the API**: in that project enable the *Google Play Android Developer API* (APIs & Services → Library → search the name → **Enable**).
3. **Create the service account**: IAM & Admin → Service Accounts → **Create service account** (name `kuotakit-backend`). You can skip the optional "grant access" steps of the Cloud wizard: the access it needs is given in Play Console. Copy its email (it ends with `.iam.gserviceaccount.com`).
4. **Create a key** for it: open the service account → **Keys → Add key → Create new key → JSON**. The browser downloads a file. **Move the downloaded file straight to `secrets/play-service-account.json`** (git-ignored); never leave it in `docs/` or the Downloads folder of a clone: the ignore pattern `*service-account*.json` only matches names that contain `service-account`, and Google names the file after the project. *(The official page does not describe the key; this is general Google Cloud knowledge.)* Treat the file like a password: move it to `secrets/play-service-account.json` of your clone of this repository (git-ignored), and delete it from Downloads.
5. **Give it access in Play Console**: **Users and permissions → Invite new users**, enter the service account's email and grant "View financial data, orders, and cancellation survey responses" and "Manage orders and subscriptions", then **Invite user**.
6. The official page says nothing about how long the access takes to apply. If the backend gets a 401 or a 403 from Google right after this, it answers `502 store_unavailable` and grants nothing; wait and retry ([docs/google-play-setup.md](google-play-setup.md)).

## H. The backend
Two stages. Start with the first one: it costs nothing and is enough to validate purchases, restore and plan changes against the real Google.

### Stage 1: your computer, with a temporary `https` address
The app needs an address it can reach from the phone, and Google (part I, later) needs an `https` address on the internet. A *Cloudflare quick tunnel* gives both, with no account and no domain ([Quick Tunnels](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)): "Quick Tunnels are for testing and development", "have no uptime guarantee", "the hostname changes each time you create a Quick Tunnel", and the URL stops working when you stop `cloudflared`. That is why this stage does **not** set up notifications (their address would change every time): purchases, restore and plan changes work without them; refunds and renewals that Google reports by notification do not (ADR 0014).

1. Install `cloudflared` (`brew install cloudflared` on macOS; *not in the official page I read*).
2. Prepare the files (from the repository root):
   ```bash
   ./gradlew :backend:installDist
   cp .env.example .env
   ```
   Edit `.env` **yourself** (the agent's guards refuse to edit it, on purpose): a real `POSTGRES_PASSWORD`; `KUOTAKIT_APP_KEYS=pk_<long random>` (it goes inside the app and is not secret) and `KUOTAKIT_SECRET_KEYS=sk_<long random>` (secret; make them with `echo "pk_$(openssl rand -hex 24)"`); and
   ```
   KUOTAKIT_GOOGLE_PACKAGE_NAME=dev.pedrorau.kuotakit.sandbox
   ```
3. Create `entitlements.yaml` (it is git-ignored) with this content:
   ```yaml
   entitlements:
     premium:
       products:
         play_store: [premium_monthly, premium_annual]   # add lifetime_unlock here if you created it
   # one_time_products:
   #   play_store: [lifetime_unlock]
   current_offering: default
   offerings:
     default:
       monthly: { play_store: premium_monthly }
       annual: { play_store: premium_annual }
   ```
   (The backend refuses to start, naming the line, if something is wrong; a store may be left out, here the App Store. **A one-time product must be written in two places**: in `products.play_store` of the entitlement that it grants, **and** in `one_time_products.play_store`; listed only in the first it would be read as a subscription. Add it to the `offerings` too if the app should sell it.)
4. Start it with the Google file on top ([docs/google-play-setup.md](google-play-setup.md)):
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.google.yml up --build
   curl localhost:8080/health
   ```
   On macOS with Colima, `DOCKER_HOST` must be exported first (AGENTS.md → Conventions).
5. In another terminal: `cloudflared tunnel --url http://localhost:8080`. It prints an address like `https://<random-words>.trycloudflare.com`. That is the **base URL** for the app. *Behind a tunnel every request reaches the backend from the tunnel's address, so all clients would share one request budget: add `KUOTAKIT_TRUST_FORWARDED_FOR=true` to `.env` only if you know the tunnel sets `X-Forwarded-For`; if in doubt leave it off for your own tests (docs/adr/0016).*
6. Check it: `curl https://<that address>/health`.
7. Keep the computer awake while you test (`caffeinate -dims` on macOS): a sleeping Mac drops the tunnel and the backend, and the app then shows its stale marker.

Limits: the tunnel allows 200 requests in flight, and the address changes every time. The example app has a settings screen where you type the backend address and the public key at run time (plan `docs/plans/2026-11-example-app.md`), so a new tunnel address needs no new build and no new upload to Play.

### Stage 2: a stable public sandbox (when stage 1 works)
This is what lets other people try the app and gives Google a fixed address for notifications. Everything below is *not in the official pages*; it is the usual way and is the cheapest thing that works, but nothing here was run.
1. A small Linux server with Docker (any provider; the sandbox is light, but this is **not measured**: watch its memory the first days), and a name for it under your domain, for example `sandbox.kuotakit.pedrorau.dev`: at your DNS provider add an `A` record to the server's public IP.
2. Run the same compose files on it, plus a reverse proxy that gets the certificate by itself. With Caddy, a `Caddyfile` of two lines is enough:
   ```
   sandbox.kuotakit.pedrorau.dev {
       reverse_proxy backend:8080
   }
   ```
   (add Caddy as a service in a compose override on the same network as `backend`, publish only ports 80 and 443).
3. **Make the backend reachable only through Caddy.** The base `docker-compose.yml` publishes `"8080:8080"` on **all** interfaces, and a port published by Docker is **not** stopped by a host firewall such as `ufw` (*general Docker knowledge, not in the official pages*). In the compose override remove that mapping instead of adding another: `ports: !reset []` on the `backend` service (Caddy reaches `backend:8080` over the compose network; `!reset` and `!override` need a recent Docker Compose: check `docker compose version`), or `ports: !override ["127.0.0.1:8080:8080"]` if you also want to reach it from the server itself. **Check from outside before going on**: `curl http://<server ip>:8080/health` must fail. Only then set `KUOTAKIT_TRUST_FORWARDED_FOR=true`: with it, anyone who reaches the backend directly could forge `X-Forwarded-For` and escape the request limits (docs/adr/0016).
4. Treat the database as disposable: it is a sandbox. Nothing in it should matter, and the app user ids in it are secrets of the users (ADR 0009), so do not publish dumps.
5. The public key (`pk_...`) is meant to be inside the app. The secret key (`sk_...`) never leaves the server.
6. Backups, updates and monitoring are your job here; the repository does not provide a deployment recipe yet (it will be written when this stage is done, as its own task).

## I. Real-time notifications (verified on 2026-11-13, with a temporary tunnel address)
*(Run for real: Pub/Sub asked for no billing; the console says "Temas", "Suscripciones", "Principales con acceso". The permission of the Pub/Sub service agent goes in the **Principales con acceso** tab of the push service account, role *Creador de tokens de cuenta de servicio*, not in its "Permisos" tab; the topic is created **without** the default subscription. In Play Console the topic goes in *Monetizar con Play → Configuración de monetización → Notificaciones del desarrollador en tiempo real*; the default content "Solo compras anuladas y suscripciones" is enough without one-time products. With a tunnel the push endpoint changes with every restart of `cloudflared`: update the subscription, and pass the audience to the backend, which restarts. The agent cannot edit `.env`: export `KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE` and `KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL` in the shell before `docker compose ... up -d backend`.)*
Without them a refund or a renewal done by Google is not seen until the app sends the receipt again ([docs/google-play-setup.md](google-play-setup.md), ADR 0014). Official pages: [Configure real-time developer notifications](https://developer.android.com/google/play/billing/getting-ready#configure-real-time-developer-notifications) and [Push subscriptions](https://docs.cloud.google.com/pubsub/docs/push): the endpoint must be "publicly addressable", Pub/Sub sends a `POST` and a `200`, `201`, `202`, `204` answer acknowledges it.
1. **Topic** in Cloud Pub/Sub (project of part G, the Pub/Sub API enabled). Give `google-play-developer-notifications@system.gserviceaccount.com` the role **Pub/Sub Publisher** on it.
2. **Push service account**: another service account (`kuotakit-push`). The Pub/Sub service agent `service-{PROJECT_NUMBER}@gcp-sa-pubsub.iam.gserviceaccount.com` needs the role **Service Account Token Creator** on it (docs/google-play-setup.md, step 2 of the notifications section).
3. **Push subscription** on the topic: endpoint `https://sandbox.kuotakit.pedrorau.dev/v1/webhooks/google`, authentication enabled with the push service account, and an **audience** (use the same URL as the endpoint).
4. In `.env` of the backend: `KUOTAKIT_GOOGLE_PUBSUB_AUDIENCE=<that audience>` and `KUOTAKIT_GOOGLE_PUBSUB_SERVICE_ACCOUNT_EMAIL=<email of the push service account>`; restart the backend.
5. Play Console → your app → **Monetize → Monetization setup** → **Real-time developer notifications**: tick *Enable real-time notifications*, **Topic name** `projects/<project_id>/topics/<topic>`, choose *Get all notifications for subscriptions and one-time products* if you created `lifetime_unlock` (otherwise *subscriptions and all voided purchases*), press **Send Test Message** (the backend logs it as ignored and answers 204) and **Save changes**.

## J. Testing cheat sheet (what the first real run showed)
All of this was seen with a license tester on the internal track; times are Google's test values ([Test your integration](https://developer.android.com/google/play/billing/test#renewals)).
- A monthly test subscription renews every **5 minutes**, an annual one every **30 minutes**, at most **6 times**; after that it expires.
- A plan change with `DEFERRED` gives a purchase with two items: the old plan keeps its end date until the swap, then Google drops it and the new plan takes over. For about 25 seconds after the swap the backend refuses the purchase (fail closed); the next sync fixes it.
- **Cancel**: Play Store → profile → Payments & subscriptions → Subscriptions → the app → Cancel. The backend sees it as `will_renew=false` only when the app sends the receipt again (Restore or opening the app), or at once with real-time notifications (part I).
- **Refund**: with the Google Play Developer API `purchases.subscriptionsv2.revoke` (a `revocationContext` with a full refund is required), or from Play Console → Order management. Same visibility rule as for a cancel.
- Without real-time notifications the backend only learns about renewals, cancellations and refunds when it receives the receipt again. That is expected, not a bug (ADR 0014).

## K. What to tell the agent, and what never to share
When parts B to H are done (I is for later), send, in the chat:
- confirmation that the package name and the product ids are the defaults, or the ones you used;
- the base URL of the backend (the tunnel's or the sandbox's) and the **public** key `pk_...`;
- the opt-in link of the internal test, if you want it in the README (it is not a secret, but it lets anyone who has it become a tester only if they are in your email list);
- what happened when you bought (the exact message, a screenshot without the account name).

**Never** paste or commit: `upload-key.jks` and its passwords, `secrets/play-service-account.json`, the secret key `sk_...`, `.env`, the Pub/Sub token. If one leaks: revoke the service account key in Google Cloud and create another; change the `sk_` key and restart the backend; for the upload key ask Google to reset it.

## If something goes wrong
| Symptom | Most likely cause |
|---|---|
| "Item not found" / the purchase screen shows no products | the subscription or its base plan is not **Active**; the app was not installed from Play (it came from Android Studio or a file); the account is not in the internal track list or did not opt in; the first upload is still not available (it can take hours) |
| The purchase screen does not show the *test* notice | the Gmail on the phone is not in the **license testers** list (part F), or another account installed the app |
| The purchase is refunded after about 3 minutes | not acknowledged: the backend did not receive the receipt or refused it (look at the backend log: `402` without account id, `409` another user's, `502` Google refused the service account) |
| The backend logs `Google Play answered HTTP 401` or `403` | the service account has no access in Play Console yet, or lacks one of the two permissions (part G step 5) |
| The backend logs `Google Play answered HTTP 404` or `410` for a purchase | the purchase token is not from the package set in `KUOTAKIT_GOOGLE_PACKAGE_NAME`, or it is too old (*the mapping of these errors is an inference, docs/adr/0013*) |
| The app says `Unauthorized` | the key in the app is in neither `KUOTAKIT_APP_KEYS` nor `KUOTAKIT_SECRET_KEYS` of the backend |
| The app throws a configuration error when it starts | the key is a secret one (`sk_...`): the SDK refuses it in `configure` on purpose; use the public `pk_...` key |
| The app cannot reach the backend after restarting `cloudflared` | the quick tunnel gives a new address each time (part H stage 1) |
| Everything is OK but the access ends after ~30 minutes | test subscriptions renew at most 6 times |
| Play Console refuses the bundle with a `minSdk` error | automatic protection needs `minSdk` 24 or higher (part D) |
| The phone is not offered the test purchase although the owner account is a tester | the phone's Gmail is not the owner's: add it to both email lists (parts D and F) |
| A tester cannot buy more than a few things | spend limits of the internal track (part D) |

## Sources (all read on 2026-11-11)
- [Set up Play Billing (developer.android.com)](https://developer.android.com/google/play/billing/getting-ready)
- [Test your Google Play Billing Library integration (developer.android.com)](https://developer.android.com/google/play/billing/test)
- [Getting started with the Google Play Developer API (developers.google.com)](https://developers.google.com/android-publisher/getting_started)
- [Test in-app billing with application licensing (Play Console Help)](https://support.google.com/googleplay/android-developer/answer/6062777)
- [Create a subscription (Play Console Help)](https://support.google.com/googleplay/android-developer/answer/140504)
- [Set up an open, closed or internal test (Play Console Help)](https://support.google.com/googleplay/android-developer/answer/3131213)
- [Create and set up your app (Play Console Help)](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Push subscriptions (Google Cloud Pub/Sub)](https://docs.cloud.google.com/pubsub/docs/push)
- [Link a Play developer account to your payments profile](https://support.google.com/googleplay/android-developer/answer/3092739)
- [App testing requirements for new personal developer accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Create a managed product](https://support.google.com/googleplay/android-developer/answer/1153481)
- [Refund and revoke purchases](https://support.google.com/googleplay/android-developer/answer/2741495)
- [Quick Tunnels (Cloudflare)](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/)
