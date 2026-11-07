# App Time Tracker

Shows how much time you spend in an app (default: YouTube) on **Android phones** and **Android TV**,
and labels the device as **Mobile** or **TV**.

There are two versions in this folder:

| Folder / file | What it is |
|---|---|
| `android/` | **The real app.** A native Android app (one APK for phone and TV). Reads real usage from Android. |
| `index.html` | An early web prototype. It can only time itself, not other apps. You can ignore it. |

## Install the APK (no commands needed)

1. Open the repo's **Actions** tab on GitHub and click the latest **Build APK** run.
2. Under **Artifacts**, download `app-time-tracker-apk` and unzip it to get `app-debug.apk`.
3. Open the APK on your phone (or copy it to your TV) and allow "install from this source".
4. Open the app and tap **Grant Usage access**. Find *App Time Tracker* in the list, switch it on,
   and go back. This is a one-time step. Android does not let apps turn it on for themselves.

## Using it

- The top line shows whether it is running on a **TV** or **Mobile**.
- YouTube is tracked by default (the TV version of YouTube on a TV).
- **Choose app** lets you pick any other installed app.
- It shows time used **Today** and over the **Last 7 days**.
- Works with touch on phones and with a remote on TV.

## What lives in this repository, and where the app is built

**This repository only contains source code.** It does not contain the Android SDK or any
compiled files.

The APK is built by GitHub, not on your computer:

1. Every push that changes `screentime-tracker/android/**` starts the workflow
   `.github/workflows/build-apk.yml`.
2. GitHub starts a **temporary Linux machine** (a "runner"). Android SDK is already installed on it.
3. The workflow installs **Java 17** and **Gradle 8.9** on that machine.
4. Gradle downloads the Android build plugin from Google's Maven repository and compiles the code.
5. The finished `app-debug.apk` is saved as a downloadable **artifact**, and the machine is thrown away.

You never install Android Studio, the SDK or Java yourself.

`UsageStatsManager` (the part that reads app usage) is built into Android itself,
so nothing extra is bundled or installed for it.

## Project layout

```
.github/workflows/build-apk.yml      GitHub build recipe
screentime-tracker/
  android/
    settings.gradle, build.gradle    Gradle project setup
    app/build.gradle                 App settings (min Android 5.0, target 14)
    app/src/main/AndroidManifest.xml Permissions + phone/TV launcher entries
    app/src/main/java/.../MainActivity.java   All app logic and UI
  index.html                         Old web prototype
```

## Share usage between devices (Firebase, free)

Optional. Without it, each device just shows its own numbers for the app it tracks.
With it, all your phones and TVs upload their usage under one **pairing code**, every device sees
everyone's numbers, and limits, warnings and the check interval come from Firebase.

### One-time setup in a browser (about 5 minutes)
1. Go to https://console.firebase.google.com and sign in with a Google account.
2. **Create a project** (any name; Google Analytics can be off). Stay on the free **Spark** plan.
3. **Build → Firestore Database → Create database.** Pick any location, **production mode**.
4. Open the **Rules** tab, replace everything with this, and press **Publish**:
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{db}/documents {
       match /sync/{code} {
         allow get, write: if true;
         match /{collection}/{doc} {
           allow get, list, write: if true;
         }
       }
     }
   }
   ```
5. Gear icon → **Project settings → General**. Copy the **Project ID** and the **Web API Key**.
   (If no Web API key is shown, click **Add app → Web (</>)**, register any name, then look again.)

### On each device
Tap **Sync settings** and enter the Project ID, the Web API Key and the **same pairing code** on every
device (make it long and random, e.g. `mani-7f3k9x2q`). Then tap **Update usage**.
You can use several phones and a TV with the same code.

### Settings that live in Firebase
Everything is under `sync` → your pairing code. Edit these in the Firebase console
(Firestore Database → Data). Number fields must be of type **number**, text fields of type **string**.

**1. Settings for all devices** – document `sync/<your code>`:

| Field | Type | Meaning |
|---|---|---|
| `checkIntervalMinutes` | number | How often each device checks in the background. Android's minimum is **15**; smaller values count as 15. Default 15. |
| `warnPercent` | number | Warn at this percentage of each app's limit, e.g. `80`. `0` or missing = no warning. |

**2. Apps** – one document per app in `sync/<your code>/apps/<app id>`:

| Field | Type | Meaning |
|---|---|---|
| `name` | string | Shown name, e.g. `YouTube` |
| `mobilePackage` | string | Android package on phones, e.g. `com.google.android.youtube` |
| `tvPackage` | string | Android package on TVs, e.g. `com.google.android.youtube.tv` |
| `limitMinutes` | number | Daily limit for this app, **all devices combined**. Missing or `0` = no limit. |

The app also fills this list for you: **Add / choose app** on any device adds the app to Firebase for that
device type (phone or TV). Picking an app never overwrites a package that is already set. Limits are
not set from the app; add `limitMinutes` in the console.
The fields `warnDay`, `warnText`, `alertDay`, `alertText` are written by the app; leave them alone.

The first device that syncs against an empty list adds the app it is already tracking (YouTube by default).

### Notifications
- When an app's combined time today reaches `warnPercent` of its limit, every device shows a warning;
  when it reaches the limit, every device shows a "Usage limit reached" notification.
  Each alert shows once per day per app, on each device.
- Checks run in the background about every `checkIntervalMinutes`, so an alert can arrive that much late.
  A change to the interval is picked up the next time a device syncs. (Instant push messages need paid
  Firebase server code; this stays free.)
- Raising the limit after an alert already fired today does not fire it again until tomorrow.
- On Android 13 or newer, allow notifications when asked. Battery-saver modes can delay background checks.

### Privacy
Uploaded: device type and model, app names and packages, and today's and the week's minutes.
Anyone who knows your project ID, API key **and** pairing code can read or change them, so keep the
code private.

## Timer

A countdown timer that runs on **one device only** (nothing goes to Firebase).

1. Enter the minutes (1 to 1,440) and tap **Start timer**.
2. The screen shows the time left and the end time.
3. When the time is up, the device shows a full-screen **"Time is up"** message. **Close** (or Back) takes you
   to the home screen.

What it does **not** do: it does not lock the phone and it does not shut down the TV. A normal Android app
cannot power off a TV, and a phone lock needs extra permissions that were not chosen.

Good to know:
- **No warning, and it cannot be stopped.** Only force-closing the app or restarting the device removes it.
  The app then says the timer was missed the next time you open it.
- **Allow "Display over other apps"** (the app shows a button for it). Without it, Android may not let the
  "Time is up" screen open over another app; the app then posts a "Time is up" notification instead.
- On a TV with no such Settings screen, grant it from a computer:
  `adb shell appops set com.example.apptimetracker SYSTEM_ALERT_WINDOW allow`
- The "Time is up" screen can be closed on purpose, so it can never lock you out.
- The timer uses an alarm-clock alarm, so an alarm icon may show in the status bar while it runs.

## Updating and TV notes

- Every build is signed with the same fixed key (`android/app/debug.keystore`), so a new APK installs
  over the old one and keeps your settings. If you installed the **very first** build, uninstall it once;
  it used a different key.
- If your TV has no "Usage access" screen, the app shows an `adb` command that grants it from a computer:
  `adb shell appops set com.example.apptimetracker GET_USAGE_STATS allow`
- Time is calculated exactly from Android's foreground/background events and refreshes every 30 seconds.

## Limits

- Phone and TV each show their own numbers. A combined view needs a backend or account.
- It is a **debug build** (debug signing key). Fine for personal use, not for the Play Store.
- Some TVs hide the "Usage access" settings screen, so the app cannot read usage there.
- iOS and Apple TV do not allow this kind of tracking.
