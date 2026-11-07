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

## Limits

- Phone and TV each show their own numbers. A combined view needs a backend or account.
- It is a **debug build** (debug signing key). Fine for personal use, not for the Play Store.
- Some TVs hide the "Usage access" settings screen, so the app cannot read usage there.
- iOS and Apple TV do not allow this kind of tracking.
