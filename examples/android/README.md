# KuotaKit Sandbox (example Android app)

An open-source app that uses the KuotaKit SDK the way an app should: the offerings the backend sells, buying, changing plan, restoring purchases, what the user may access, and the last known answer without a network. Plan: [`docs/plans/2026-11-example-app.md`](../../docs/plans/2026-11-example-app.md). Package `dev.pedrorau.kuotakit.sandbox`.

It decides nothing: **access is only what the backend answers**. It has no secret in it (a secret key `sk_...` is refused), and the price text of Google Play is not shown because the SDK does not give it yet.

## Run it against your own backend
1. Start a backend (`README.md` of the repository, `docs/google-play-setup.md` for the real Google Play).
2. Build and install the debug app: `./gradlew :examples:android:installDebug`.
3. Open the app → **Settings** → type the address of your backend and the **public** key (`pk_...`) → **Save and restart**. The SDK is configured once per run of the app, so the app restarts itself.
   - `https` is required. Plain `http` works only in the debug build, and only for `localhost`, `127.0.0.1` and `10.0.2.2` (the computer that runs the emulator).
4. To start with other defaults instead of typing them: put them in `local.properties` of the repository root (`sandbox.baseUrl=...`, `sandbox.apiKey=...`), or on the command line (`-Psandbox.baseUrl=... -Psandbox.apiKey=...`). The committed defaults are in [`sandbox.properties`](sandbox.properties) (the backend address and key are empty until the public sandbox exists). `privacyPolicyUrl` there is the link of the Settings screen to the privacy policy, as Google Play asks; like the other two it can be overridden in `local.properties` or with `-Psandbox.privacyPolicyUrl=...`.

Buying needs a device with the Google Play Store, the app installed from a Play test track, a license tester account and products created in Play Console: [`docs/sandbox-play-console.md`](../../docs/sandbox-play-console.md) lists all of it, in order. Without them the app starts, lists the offerings and fails to buy with a clear message, granting nothing.

## Build the signed release (the maintainer)
The release build is signed like the maintainer's other apps: a `keystore.properties` at the **repository root** (git-ignored; copy [`keystore.properties.example`](../../keystore.properties.example)) with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, and the upload key at the path in `storeFile` (default `examples/android/upload-keystore.jks`, also git-ignored). Without that file the release is built unsigned.
```bash
./gradlew :examples:android:bundleRelease     # the .aab for Play Console
```
The release is minified with R8 (`proguard-rules.pro`). Neither the key nor its passwords are ever in the repository.

## Test and check
```bash
./gradlew :examples:android:testDebugUnitTest :examples:android:lintDebug :examples:android:assembleRelease
./gradlew :architecture-tests:test    # the app uses only the public API of the SDK (ExampleAppRules)
```
The view models talk to the SDK only through `SubscriptionsGateway`, which the tests replace, so they run without a device.
