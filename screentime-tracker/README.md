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

## Share usage between phone and TV (Firebase, free)

Optional. Without it, each device just shows its own numbers.

**One-time setup in a browser (about 5 minutes):**
1. Go to https://console.firebase.google.com and sign in with a Google account.
2. **Create a project** (any name; you can turn Google Analytics off). Stay on the free **Spark** plan.
3. In the left menu choose **Build → Firestore Database → Create database**. Pick any location and
   **Start in production mode**.
4. Open the **Rules** tab, replace everything with the rules below, and press **Publish**:
   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{db}/documents {
       match /sync/{code} {
         allow get, write: if true;
         match /devices/{id} {
           allow get, list, write: if true;
         }
       }
     }
   }
   ```
5. Click the gear icon → **Project settings → General**. Copy the **Project ID** and the **Web API Key**.
   (If no Web API key is shown, click **Add app → Web (</>)**, register any name, then look again.)

**In the app, on each device:** tap **Sync settings** and enter the Project ID, the Web API Key and the
same **pairing code** (make it long and random, for example `mani-7f3k9x2q`). Then tap **Update usage**.

**Daily limit:** tap **Set daily limit** and enter minutes. The limit is stored in Firebase
(`sync/<your code>` → `limitMinutes`), so you can also change it there. It applies to the
**combined** time of all your devices.

### Notifications

When the combined time for today reaches the limit, every device shows a "Usage limit reached"
notification, once per day.

- The check runs in the background about **every 15 minutes** on each device, so an alert can arrive up to
  about 15 minutes late. (Instant push messages need paid Firebase server code; this stays free.)
- The device that crosses the limit only notices when its own check runs, or when you tap **Update usage**.
- If you raise the limit after an alert already fired today, there is no second alert until tomorrow.
- On Android 13 or newer, allow notifications when asked. Android may pause background checks on devices
  in battery-saver mode.

### Privacy

Only these numbers are uploaded: device type and model, the tracked app's name, and today's and the
week's minutes. Anyone who knows your project ID, API key **and** pairing code could read or change them,
so keep the code private.

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
