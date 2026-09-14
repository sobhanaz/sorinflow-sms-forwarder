# SorinFlow Forwarder

An Android app that forwards Divar's one-time-code SMS to a
[SorinFlow](https://sorinflow.com) server within seconds. It runs on the phone
that holds the Divar account's SIM; when Divar sends a **contact-info code** or a
**login code**, the app POSTs it, signed, to `/api/scraper/otp-inbound`, and pings
`/api/scraper/forwarder-heartbeat` every five minutes so the panel knows the
phone is alive.

It is a fork of Konstantin Bogomolov's open-source
[Incoming SMS Gateway](https://github.com/bogkonstantin/android_income_sms_gateway_webhook)
(MIT). The generic SMS-to-webhook engine, rule editor, backup/import and syslog
viewer are all still there; the SorinFlow layer sits on top of them.

## How it works

1. Android delivers the SMS to the app's manifest receiver (this works even
   when an OEM battery manager has killed the app's service).
2. The message is matched against the two rules that setup installed: sender
   `Divar` plus the text `اطلاعات تماس` (contact code) or `کد تایید` (login code).
3. The request is sent **immediately on a background thread** (10 s connect /
   15 s read timeout), not through a scheduler. The status card shows the
   measured delay between SMS arrival and the server's answer for every
   message, so you can see the real latency on your network.
4. If that first attempt fails, a WorkManager job (expedited on Android 12+)
   retries after 5 s, 10 s, 20 s, 30 s, 30 s… and stops 100 s after the SMS was
   received. A code older than that is never sent; it is stored as failed and
   shown on the status card.
5. Every request carries `X-Signature`, a hex HMAC-SHA-256 of the raw body
   with the shared secret. All traffic is HTTPS; cleartext is disabled.

Request body sent for a code (the server contract):

```json
{"kind":"contact","account":"09123456789","code":"523969","text":"<raw sms>",
 "sim":"sim1","sentStamp":1757700000000,"receivedStamp":1757700001200,
 "battery":83,"network":"mobile"}
```

`kind` is `contact` or `login` (the test button sends `test`). `code` is the six
digits after `Code:`; if the SMS uses another form the server extracts the code
from `text` itself. Heartbeat body:
`{"account":"09123456789","battery":83,"network":"wifi","version":"3.0.0"}`.

## Installing

The app is **not on Google Play and cannot be**: Play's SMS permission policy
does not allow "forward incoming SMS to a URL" apps. Install the APK from the
releases page or build it yourself (below), then sideload it:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

or copy the APK to the phone and open it from a file manager.

**Play Protect will warn about the app.** That is a false positive: reading SMS
and sending it to a server is the same pattern as SMS-stealing malware, and the
scanner cannot know you configured the destination. Choose *Install anyway* /
*More details → Install anyway*. If Play Protect removed the app, temporarily
turn it off (Play Store → profile → Play Protect → gear → *Scan apps with Play
Protect*), install, and turn it back on.

### Xiaomi / HyperOS (and most Chinese OEM ROMs)

These ROMs kill background apps aggressively. Do all of the following once,
or codes will stop arriving after a few hours:

1. **Allow restricted settings** for the app: Settings → Apps → SorinFlow
   Forwarder → ⋮ (top right) → *Allow restricted settings*. Android 13+ blocks
   sideloaded apps from sensitive permissions until you do this.
2. **SMS permission**: Settings → Apps → SorinFlow Forwarder → Permissions →
   SMS → *Allow*. Also allow **Notifications**.
3. **Battery saver**: Settings → Apps → SorinFlow Forwarder → Battery saver →
   **No restrictions**.
4. **Autostart**: Settings → Apps → Permissions → Autostart → enable the app.
5. **Lock it in Recents**: open the app, open the recent-apps view, long-press
   (or pull down) the app card and tap the lock icon.
6. Settings → Apps → SorinFlow Forwarder → **turn off "Pause app activity if
   unused"** / "Manage app if unused" / "Remove permissions if app isn't used".
7. In Google Messages (or the default SMS app) **disable RCS**: Settings → RCS
   chats → *Turn off RCS chats*. RCS messages never reach the SMS API.
8. Keep the phone on a charger and, ideally, on Wi-Fi plus mobile data.

After that, the `F` icon in the status bar means the forwarder service is
running.

## Setup (under two minutes per phone)

1. Open the app and grant the SMS and notification permissions it asks for.
2. Tap **Set up** on the SorinFlow card (also in the ⋮ menu → *SorinFlow setup*).
3. Fill in:
   * **SorinFlow server URL**: `https://sorinflow.com` (https only; a pasted
     endpoint path or trailing slash is stripped automatically).
   * **Divar account phone number**: the number of the SIM in this phone, as
     the Divar account knows it, e.g. `09123456789`. Persian digits are fine.
   * **Shared secret**: the value configured on the server.
4. Tap **Save**. The app creates the two Divar rules, turns on the heartbeat
   and starts the service. The settings are stored encrypted (Android
   Keystore); the secret never appears in the rule list or in an exported
   backup.
5. Tap **Send test to server**. Within a couple of seconds a toast shows the
   HTTP status, the round-trip time and the server's answer (`test`), and the
   card's server line turns to *reachable*. The card also shows the last
   forwarded message (kind, masked code, HTTP status, round trip, delay after
   the SMS) and how many messages, if any, are stored as failed. *Retry N
   failed* re-sends stored messages, except Divar codes older than 100 s,
   which it discards because they can no longer be used.
6. Optional: send yourself a Divar code and watch the card update.

To clone a configured phone, export the rules from *Settings → Backup*, import
them on the new phone, and run *Set up* there once (the secret is device-local
by design).

The ⋮ menu → *Syslog* shows the app's error log from logcat when something
goes wrong (HTTP 401 means the secret differs from the server's; `stale_code`
means the code arrived after the server's request had already expired).

## Building

Requirements: JDK 17 and the Android SDK with platform 34 and build-tools 34.
Point the SDK at the project with `ANDROID_HOME` or a `local.properties`
containing `sdk.dir=/path/to/android-sdk` (git-ignored).

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug        # debug APK
JAVA_HOME=/path/to/jdk17 ./gradlew testDebugUnitTest    # JVM unit tests
JAVA_HOME=/path/to/jdk17 ./gradlew connectedAndroidTest # needs a device/emulator
```

If `dl.google.com` is blocked on your network, the build falls through to a
mirror of Google's Maven repository declared in `build.gradle`.

### Signed release APK

The signing keystore lives **outside the repository**. Create one once:

```bash
mkdir -p ~/.sorinflow-keys && chmod 700 ~/.sorinflow-keys
keytool -genkeypair -keystore ~/.sorinflow-keys/sorinflow-forwarder.jks \
  -alias sorinflow -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=SorinFlow Forwarder, O=Tecso, C=IR"
```

and describe it in `~/.sorinflow-keys/keystore.properties` (absolute path):

```properties
storeFile=/Users/you/.sorinflow-keys/sorinflow-forwarder.jks
storePassword=...
keyAlias=sorinflow
keyPassword=...
```

`app/build.gradle` reads that file (or the one named by the
`SORINFLOW_KEYSTORE_PROPERTIES` environment variable) and signs the release
build with it; without the file the release APK is built unsigned.

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Keep the keystore and its password safe: an update signed with a different key
cannot be installed over the existing app. Bump `versionCode` and
`versionName` in `app/build.gradle` for every release.

## Advanced: generic forwarding rules

Everything the upstream app can do still works: add rules with the **+**
button for any sender, filter by regex, use custom JSON templates with
`%from%`, `%text%`, `%sentStamp%`, `%receivedStamp%`, `%sim%`, `%battery%`,
`%network%`, `%version%` and `%Regex=…%`, sign with HMAC, store failed
messages, and export/import rules. See the
[upstream README](https://github.com/bogkonstantin/android_income_sms_gateway_webhook#readme)
for the template language. Note that this fork is HTTPS-only.

## License

MIT. Copyright (c) 2021 Konstantin Bogomolov (upstream), SorinFlow additions
(c) 2026 Tecso. See `LICENSE.txt`.

---

## راهنمای سریع (فارسی)

این برنامه کدهای پیامکی دیوار («کد امنیتی دریافت اطلاعات تماس» و «کد تایید») را
در چند ثانیه به سرور سورین‌فلو می‌فرستد. روی گوشی‌ای نصب می‌شود که سیم‌کارت
حساب دیوار در آن است.

**نصب:** فایل APK را با `adb install` یا از فایل‌منیجر نصب کنید. هشدار Play
Protect را با «به هر حال نصب شود» رد کنید (هشدار اشتباه است؛ برنامه فقط به
سروری که خودتان وارد می‌کنید پیام می‌فرستد).

**شیائومی / HyperOS:** در تنظیمات برنامه، «Allow restricted settings» را بزنید،
دسترسی پیامک و اعلان را بدهید، «Battery saver» را روی «No restrictions» بگذارید،
«Autostart» را روشن کنید، برنامه را در فهرست برنامه‌های اخیر قفل کنید، گزینهٔ
«Pause app activity if unused» را خاموش کنید و در Google Messages گزینهٔ RCS را
غیرفعال کنید.

**راه‌اندازی:** برنامه را باز کنید، دسترسی‌ها را بدهید، روی «راه‌اندازی» بزنید،
آدرس سرور (`https://sorinflow.com`)، شمارهٔ موبایل حساب دیوار و کلید محرمانه
را وارد کنید و ذخیره کنید. سپس «ارسال آزمایشی به سرور» را بزنید؛ باید پاسخ
HTTP 200 را ببینید. وقتی آیکون `F` در نوار وضعیت هست، برنامه فعال است.
