# App Time Tracker

Tracks how long this app (treated as "YouTube") is used, separately for **Mobile** and **TV**.

## Run
Open `index.html` in a browser, or serve it: `python3 -m http.server 8000`.
On a phone or TV browser/WebView, open the served URL.

## How it works
- Detects TV vs mobile from the user agent (Tizen, webOS, Android TV, Fire TV, Apple TV, Roku, etc.) with a screen-size fallback.
- Counts time only while the app is in the foreground (Page Visibility API).
- Stores milliseconds per device type in `localStorage`.
- Change `APP_NAME` in `index.html` to rename the tracked app.

## Limitation / next step
This version tracks only *itself*. Each device keeps its own totals; they aren't merged across devices.
To track a *different* app (e.g. the real YouTube) on Android / Android TV, build a native app that uses
`UsageStatsManager` with package name `com.google.android.youtube` (needs the Usage Access permission),
and add a small backend to merge phone + TV totals.
