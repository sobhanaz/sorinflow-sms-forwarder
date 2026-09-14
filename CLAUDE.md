# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**SorinFlow Forwarder** (applicationId `ir.sorinflow.smsforwarder`): an Android app that forwards Divar one-time-code SMS to a SorinFlow server within seconds. It is a fork of `bogkonstantin/android_income_sms_gateway_webhook` (MIT), whose generic SMS-to-webhook engine is kept intact underneath a small SorinFlow layer. The upstream remote is named `upstream`; keep the upstream file structure and the Java package `tech.bogomolov.incomingsmsgateway` (only the `applicationId` changed) so `git merge upstream/master` stays feasible, and put SorinFlow-specific code in the `SorinFlow*` / `DirectDelivery` / `RetrySchedule` / `OtpCodes` / `DeliveryStatus` / `StatusCard` classes rather than in upstream files where possible. Prefer small, focused changes.

## Build & test

Gradle (wrapper committed, Gradle 9.4.1) + AGP 9.2.1, `compileSdk 34`, `targetSdk 34`, `minSdk 26`. Source is Java. AGP 9.x needs **JDK 17** (`JAVA_HOME=<jdk17> ./gradlew ...`). `local.properties` (git-ignored) or `ANDROID_HOME` points at the SDK. `build.gradle` lists a Google-Maven mirror after `google()` because `dl.google.com` is blocked on some networks.

```bash
./gradlew assembleDebug        # build debug APK
./gradlew assembleRelease      # release APK; signed only if ~/.sorinflow-keys/keystore.properties exists (see README)
./gradlew testDebugUnitTest    # JVM unit tests (no device needed)
./gradlew connectedAndroidTest # instrumented tests (REQUIRES a device/emulator)
```

Tests in `app/src/androidTest/` are **instrumented** (device/emulator); `app/src/test/` holds pure-JVM tests, which is where logic that touches no Android API should be tested (`ForwardingConfigPrepareMessageTest`, `RetryScheduleTest`, `OtpCodesTest`, `SorinFlowRulesTest`, ...). Run one instrumented class with `-Pandroid.testInstrumentationRunnerArguments.class=tech.bogomolov.incomingsmsgateway.WebhookCallerTest`. CI (`.github/workflows/tests.yml`) runs both suites.

Convention: a `SharedPreferences`-backed model gets an instrumented model test (`FailedMessageTest`, `HeartbeatSettingsTest`, `SorinFlowSettingsTest`, `SorinFlowRulesApplyTest`); a screen's form validation gets an Espresso test (`MainActivityTest`, `SettingsActivityTest`, `SetupActivityTest`). Espresso tests must not exercise paths that start the foreground service (a valid "save"), and `MainActivity` must not auto-redirect on launch (Espresso launches it directly) — first-run setup is the status card's **Set up** button plus a menu item. `SmsReceiverTest` mocks `callWebHook(ForwardingConfig, String, String, String, long)`; keep that signature.

Without an Android SDK, `scratch`-style standalone checks are possible by compiling against Robolectric's `android-all` jar plus the AndroidX jars (see git history of this file's session if needed); the real build is still Gradle.

## Architecture

Data flow: **SMS arrives → matched against configs → sent immediately on a background thread → WorkManager fallback with a deadline-bound retry ladder.**

- **`SmsReceiverService`** — foreground `Service` (`foregroundServiceType="specialUse"`, no daily runtime limit on Android 14+) that keeps the process alive and hosts the heartbeat ping. It registers **no** SMS receiver (`SmsBroadcastReceiver` is manifest-declared; a runtime receiver would double-deliver). `SmsReceiverService.start(Context[, action])` is the one safe way to start/poke it (catches Android 12+ background-start refusals); `startForeground` failures are caught and logged, never crash-loop. First heartbeat 5 s after (re)start, then every interval. When the heartbeat URL is the SorinFlow one, the ping is the signed JSON body from `SorinFlowClient`; otherwise the upstream empty POST.

- **`SmsBroadcastReceiver.onReceive`** — dispatch logic (sender exact / `*` / regex fail-closed, per-rule text filter regex fail-open, SIM slot heuristic). `callWebHook` builds the worker `Data` and hands it to **`DirectDelivery.send`**. For SorinFlow rules (identified by rule key prefix `sorinflow_`) it sets `DATA_SIGN_WITH_SETUP_SECRET` (secret resolved from `SorinFlowSettings` at send time, never copied into `Data`/WorkManager's DB/failed store) and `DATA_DEADLINE = receivedStamp + 100 s`.

- **`DirectDelivery`** — single-thread executor; ensures the service is running, runs one attempt via `RequestWorker.attempt` (10 s connect / 15 s read), and only on a retryable failure enqueues `RequestWorker` (expedited on API 31+, `RUN_AS_NON_EXPEDITED_WORK_REQUEST`). A permanent error (bad URL/headers) is stored as failed right away.

- **`RequestWorker`** — `attempt()` is the single "one HTTP try" routine (builds `Request`, resolves the secret, records `DeliveryStatus`). `doWork` has two modes: with `DATA_DEADLINE` it loops **inside one run** on `RetrySchedule` (5, 10, 20, 30, 30… s) and gives up, storing the message via `FailedMessage`, once the next retry would pass the deadline — WorkManager's own backoff (10 s minimum + scheduling latency) can't honour a 100 s cutoff; without a deadline it is the upstream behaviour (`Result.retry()` + exponential backoff up to `maxRetries`). `FailedMessage` deliberately does not persist the deadline; a manual "Retry N failed" uses the classic path for ordinary rules and **drops** SorinFlow entries whose receive time is more than 100 s old (`RetrySchedule.isPastDeadline`) instead of re-sending them.

- **`Request`** — `HttpURLConnection` wrapper. Defaults: 10 s connect / 20 s read (`setTimeouts` overrides), reads the first 4 KB of the response body (`getResponseBody`), measures `getElapsedMillis`, exposes `getFailure`/`getResult`. Returns `RESULT_SUCCESS` / `RESULT_RETRY` (non-2xx, IOException) / `RESULT_ERROR` (malformed URL, bad headers, SSL setup). HMAC: `X-Signature` = lowercase hex HMAC-SHA-256 over the UTF-8 body bytes. "Ignore SSL" path kept for hand-made rules.

- **`ForwardingConfig`** — rule model + persistence (one JSON string per rule in the `phones` SharedPreferences file; `getAll()` is the source of truth; legacy bare-URL fallback; guarded `json.has` reads). New field → `KEY_*`, getter/setter, `toJson()`, guarded read in `fromStoredValue`. `prepareMessage` substitutes `%from% %text% %sentStamp[=fmt]% %receivedStamp[=fmt]% %sim% %version% %battery% %power% %network% %Regex=…%` in one pass with `Matcher.quoteReplacement` (keep it). The preference file name string `key_phones_preference` must be `phones` in **every** locale.

- **`SorinFlowSettings`** — base URL, account phone, shared secret in `EncryptedSharedPreferences` (file `sorinflow_secure`, cached per process, plain-prefs fallback if the Keystore is broken). **`SorinFlowRules`** — builds the two rules with fixed keys `sorinflow_contact` / `sorinflow_login` (sender `Divar`, filters `اطلاعات تماس` / `کد تایید`, URL `<base>/api/scraper/otp-inbound`, template `{"kind":…,"account":…,"code":"%Regex=Code:\s*(\d{6})%","text":"%text%","sim":"%sim%","sentStamp":%sentStamp%,"receivedStamp":%receivedStamp%,"battery":%battery%,"network":"%network%"}`, HMAC on with a null stored secret, fixed-length body, https only, store-failed on), `apply()` saves them (idempotent) and enables the heartbeat (`<base>/api/scraper/forwarder-heartbeat`, 5 min), `normalizeBaseUrl` enforces https and strips pasted endpoint paths, `resolveSecret` picks setup vs rule secret. **`SorinFlowClient`** — signed heartbeat and the `kind:"test"` request. **`OtpCodes`** — Persian/Arabic→ASCII digits, code extraction (`Code:` label, else standalone 6 digits), masking (`••••69`), phone normalisation. **`DeliveryStatus`** — last message / last heartbeat outcome in the `delivery_status` prefs file; **`StatusCard`** (list header on `MainActivity`) listens to it and hosts **Set up** / **Send test to server** (a test's outcome is recorded as the server-reachability line, not as the last forwarded message). **`SetupActivity`** — the three-field form; save = settings.save + `SorinFlowRules.apply` + poke the service.

- **`HeartbeatSettings` / `SettingsActivity`** — global heartbeat settings (own prefs file) + rules export/import via SAF (`startActivityForResult`). **`FailedMessage`** — opt-in store of undelivered payloads (`Retry N failed` in the action bar). **UI** — `MainActivity` (status card header + rule list + permissions + syslog), `ListAdapter`, `ForwardingConfigDialog` (add/edit; hides "ignore SSL" and skips the empty-secret check for SorinFlow rules; test-result toast posted via a main-thread Handler, no runtime broadcast receiver — Android 14 requires exported flags for those).

## Conventions & gotchas

- Bump `versionCode` **and** `versionName` in `app/build.gradle` for any release.
- Server contract is fixed (see README); the app is built to it, never the other way round.
- Manifest hardening is intentional: `usesCleartextTraffic="false"`, `allowBackup="false"`, service `exported="false"`. Keep `minSdk 26`; `Build.VERSION` guards below O are dead code and may be removed when touched.
- Strings live in `values/strings.xml` (source of truth) **and** `values-ru`, `values-fa` — add every new key to all three (a missing key silently falls back to English; a script comparing `<string name=…>` sets across the files is a quick check). Persian is RTL; `supportsRtl` is on.
- Android 14: any `registerReceiver` for a non-system broadcast needs `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`; foreground services need a type; expedited WorkManager below API 31 would need `getForegroundInfo()` (we don't expedite there).
- JVM unit tests: the mockable `android.jar` stubs `org.json`; `testImplementation 'org.json:json'` supplies the real one, and `unitTests.returnDefaultValues = true` covers `Log` and friends. `Request.setJsonHeaders`/`setSignatureHeader` are no-ops when the constructor failed (malformed URL) — keep that guard.
- Store/F-Droid metadata under `fastlane/` is upstream's and unused by this fork.
