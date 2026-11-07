package com.example.apptimetracker;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Talks to Firebase: uploads this device's usage per app, reads the other devices' usage and the
 * settings, and shows "warning" / "limit reached" notifications.
 *
 * Layout in Firestore (everything lives under the pairing code):
 *   sync/{code}                  checkIntervalMinutes, warnPercent             (settings for all devices)
 *   sync/{code}/apps/{appId}     name, mobilePackage, tvPackage, limitMinutes, (one entry per tracked app)
 *                                warnDay, warnText, alertDay, alertText         (alert state, written by the app)
 *   sync/{code}/usage/{deviceId}_{appId}
 *                                deviceId, deviceName, type, appId, day, todayMs, weekMs, updatedAt
 */
final class SyncManager {
    private SyncManager() { }

    /** An app this device tracks (already narrowed to the package that belongs to this device type). */
    static final class TrackedApp {
        String id, name, pkg;
    }

    static final class DeviceRow {
        String type, deviceName;
        long todayMs, weekMs, updatedAt;
        boolean me;
    }

    static final class AppResult {
        String id, name;
        long limitMinutes, combinedTodayMs;
        final List<DeviceRow> rows = new ArrayList<>();
    }

    static final class Summary {
        final List<AppResult> apps = new ArrayList<>();
        long intervalMinutes = 15;
        long warnPercent;       // 0 = no warning
        int notifications;      // how many notifications this run showed
        String error;           // null when everything worked
    }

    /** One entry of the app list as stored in Firebase. */
    private static final class AppCfg {
        String id, name, mobilePkg, tvPkg, alertDay, alertText, warnDay, warnText;
        long limitMinutes;
        String pkgFor(boolean tv) { return tv ? tvPkg : mobilePkg; }
    }

    // ------------------------------------------------------------------ main sync

    /** Upload this device's usage, read everyone's, apply limits, show notifications. Never throws. */
    static Summary sync(Context c) {
        Summary s = new Summary();
        try {
            if (!Config.syncConfigured(c)) { s.error = "Sync is not set up."; return s; }
            if (!Usage.hasAccess(c)) { s.error = "Usage access is off."; return s; }

            Firestore fs = new Firestore(Config.get(c, Config.PROJECT), Config.get(c, Config.API_KEY));
            String code = Config.get(c, Config.CODE);
            String myId = Config.deviceId(c);
            String today = Usage.today();
            boolean tv = Usage.isTv(c);
            String type = tv ? "tv" : "mobile";

            // 1. Settings that apply to every device
            JSONObject shared = fs.getFields("sync/" + code);
            s.intervalMinutes = Math.max(15, Firestore.num(shared, "checkIntervalMinutes", 15));
            s.warnPercent = Math.max(0, Math.min(100, Firestore.num(shared, "warnPercent", 0)));
            Config.set(c, Config.INTERVAL, String.valueOf(s.intervalMinutes));

            // 2. The list of apps (the first device seeds it with its own app)
            List<AppCfg> apps = readApps(fs, code);
            if (apps.isEmpty()) {
                String pkg = Usage.targetPackage(c);
                if (pkg != null) {
                    addApp(c, Usage.label(c, pkg), pkg);
                    apps = readApps(fs, code);
                }
            }

            // 3. This device's usage for every app that has a package for this device type
            List<String> pkgs = new ArrayList<>();
            for (AppCfg a : apps) {
                String p = a.pkgFor(tv);
                if (!p.isEmpty() && !pkgs.contains(p)) pkgs.add(p);
            }
            Map<String, long[]> usage = Usage.foregroundMs(c, pkgs);
            if (usage == null) { s.error = "Could not read usage data."; return s; }

            // Remember which apps this device tracks, so the screen can show them without the network
            JSONArray cache = new JSONArray();
            List<Firestore.Write> writes = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (AppCfg a : apps) {
                String p = a.pkgFor(tv);
                if (p.isEmpty()) continue;
                long[] u = usage.get(p);
                cache.put(new JSONObject().put("id", a.id).put("name", a.name).put("pkg", p));

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deviceId", myId);
                m.put("deviceName", Build.MODEL == null ? "device" : Build.MODEL);
                m.put("type", type);
                m.put("appId", a.id);
                m.put("day", today);
                m.put("todayMs", u[0]);
                m.put("weekMs", u[1]);
                m.put("updatedAt", now);
                writes.add(new Firestore.Write("sync/" + code + "/usage/" + myId + "_" + a.id, m));
            }
            Config.set(c, Config.APPS_CACHE, cache.toString());
            fs.commit(writes);

            // 4. Everyone's usage, grouped by app
            Map<String, AppResult> byApp = new LinkedHashMap<>();
            for (AppCfg a : apps) {
                AppResult r = new AppResult();
                r.id = a.id;
                r.name = a.name;
                r.limitMinutes = a.limitMinutes;
                byApp.put(a.id, r);
                s.apps.add(r);
            }
            for (Firestore.Doc d : fs.list("sync/" + code + "/usage")) {
                AppResult r = byApp.get(Firestore.str(d.fields, "appId"));
                if (r == null) continue;
                DeviceRow row = new DeviceRow();
                row.type = Firestore.str(d.fields, "type");
                row.deviceName = Firestore.str(d.fields, "deviceName");
                row.weekMs = Firestore.num(d.fields, "weekMs", 0);
                row.updatedAt = Firestore.num(d.fields, "updatedAt", 0);
                // Ignore "today" values that were uploaded on a previous day
                row.todayMs = today.equals(Firestore.str(d.fields, "day")) ? Firestore.num(d.fields, "todayMs", 0) : 0;
                row.me = myId.equals(Firestore.str(d.fields, "deviceId"));
                r.combinedTodayMs += row.todayMs;
                r.rows.add(row);
            }

            // 5. Limits and warnings, per app
            for (AppCfg a : apps) {
                AppResult r = byApp.get(a.id);
                long limitMs = a.limitMinutes * 60_000L;
                if (limitMs <= 0) continue;

                String alertDay = a.alertDay, alertText = a.alertText;
                String warnDay = a.warnDay, warnText = a.warnText;

                if (r.combinedTodayMs >= limitMs) {
                    if (!today.equals(alertDay)) {
                        alertText = a.name + ": " + Usage.format(r.combinedTodayMs)
                                + " used today across your devices (limit " + Usage.format(limitMs) + ").";
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("alertDay", today);
                        m.put("alertText", alertText);
                        fs.write("sync/" + code + "/apps/" + a.id, m);
                        alertDay = today;
                    }
                } else if (s.warnPercent > 0 && r.combinedTodayMs * 100 >= limitMs * s.warnPercent
                        && !today.equals(warnDay)) {
                    warnText = a.name + ": " + Usage.format(r.combinedTodayMs) + " used today across your devices – "
                            + s.warnPercent + "% of your " + Usage.format(limitMs) + " limit.";
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("warnDay", today);
                    m.put("warnText", warnText);
                    fs.write("sync/" + code + "/apps/" + a.id, m);
                    warnDay = today;
                }

                // Show each alert once per day on this device
                if (today.equals(alertDay)) {
                    if (firstTimeToday(c, "limit", a.id, today)) {
                        Notifier.show(c, notificationId("limit", a.id), "Usage limit reached",
                                alertText.isEmpty() ? a.name + ": daily limit reached." : alertText);
                        s.notifications++;
                    }
                } else if (today.equals(warnDay)) {
                    if (firstTimeToday(c, "warn", a.id, today)) {
                        Notifier.show(c, notificationId("warn", a.id), "Approaching usage limit",
                                warnText.isEmpty() ? a.name + ": close to the daily limit." : warnText);
                        s.notifications++;
                    }
                }
            }
        } catch (Exception e) {
            s.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return s;
    }

    private static List<AppCfg> readApps(Firestore fs, String code) throws java.io.IOException {
        List<AppCfg> out = new ArrayList<>();
        for (Firestore.Doc d : fs.list("sync/" + code + "/apps")) {
            AppCfg a = new AppCfg();
            a.id = d.id;
            a.name = Firestore.str(d.fields, "name");
            if (a.name.isEmpty()) a.name = d.id;
            a.mobilePkg = Firestore.str(d.fields, "mobilePackage");
            a.tvPkg = Firestore.str(d.fields, "tvPackage");
            a.limitMinutes = Math.max(0, Firestore.num(d.fields, "limitMinutes", 0));
            a.alertDay = Firestore.str(d.fields, "alertDay");
            a.alertText = Firestore.str(d.fields, "alertText");
            a.warnDay = Firestore.str(d.fields, "warnDay");
            a.warnText = Firestore.str(d.fields, "warnText");
            out.add(a);
        }
        return out;
    }

    private static boolean firstTimeToday(Context c, String kind, String appId, String today) {
        String key = "n_" + kind + "_" + appId;
        if (today.equals(Config.get(c, key))) return false;
        Config.set(c, key, today);
        return true;
    }

    private static int notificationId(String kind, String appId) {
        return (kind + appId).hashCode() & 0x7fffffff;
    }

    // ------------------------------------------------------------------ app list

    /** Apps this device tracks: the Firebase list from the last sync, or the app picked on this device. */
    static List<TrackedApp> trackedApps(Context c) {
        List<TrackedApp> out = new ArrayList<>();
        if (Config.syncConfigured(c)) {
            String cache = Config.get(c, Config.APPS_CACHE);
            if (!cache.isEmpty()) {
                try {
                    JSONArray arr = new JSONArray(cache);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        TrackedApp t = new TrackedApp();
                        t.id = o.getString("id");
                        t.name = o.getString("name");
                        t.pkg = o.getString("pkg");
                        out.add(t);
                    }
                } catch (JSONException ignored) {
                    out.clear();
                }
            }
            if (!out.isEmpty()) return out;
        }
        String pkg = Usage.targetPackage(c);
        if (pkg != null) {
            TrackedApp t = new TrackedApp();
            t.name = Usage.label(c, pkg);
            t.id = slug(t.name, pkg);
            t.pkg = pkg;
            out.add(t);
        }
        return out;
    }

    /**
     * Adds an app picked on this device to the shared list in Firebase (for this device's type).
     * Never changes a package that is already set.
     *
     * @return a message to show the user, or null if everything is fine
     */
    static String addApp(Context c, String name, String pkg) {
        try {
            Firestore fs = new Firestore(Config.get(c, Config.PROJECT), Config.get(c, Config.API_KEY));
            String code = Config.get(c, Config.CODE);
            String id = slug(name, pkg);
            String field = Usage.isTv(c) ? "tvPackage" : "mobilePackage";

            JSONObject existing = fs.getFields("sync/" + code + "/apps/" + id);
            String current = Firestore.str(existing, field);
            if (!current.isEmpty() && !current.equals(pkg)) {
                return "\"" + name + "\" is already in Firebase with a different " + (Usage.isTv(c) ? "TV" : "mobile")
                        + " package (" + current + "). Not changed. Edit it in the Firebase console if needed.";
            }
            if (current.equals(pkg)) return null;

            Map<String, Object> m = new LinkedHashMap<>();
            if (Firestore.str(existing, "name").isEmpty()) m.put("name", name);
            m.put(field, pkg);
            fs.write("sync/" + code + "/apps/" + id, m);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /** Turns an app name into a safe Firestore document id, e.g. "YouTube Kids" -> "youtube-kids". */
    static String slug(String name, String fallback) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) s = fallback.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "app" : s;
    }
}
