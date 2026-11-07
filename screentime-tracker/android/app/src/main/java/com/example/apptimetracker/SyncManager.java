package com.example.apptimetracker;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uploads this device's usage to Firestore, reads the other devices' usage and the shared limit,
 * and shows a notification when the combined limit is reached.
 *
 * Layout in Firestore:
 *   sync/{code}                 limitMinutes, alertDay, alertText, alertBy   (shared settings)
 *   sync/{code}/devices/{id}    type, name, app, day, todayMs, weekMs, updatedAt
 */
final class SyncManager {
    private SyncManager() { }

    static final class Row {
        String type, name, app;
        long todayMs, weekMs, updatedAt;
        boolean me;
    }

    static final class Summary {
        final List<Row> devices = new ArrayList<>();
        long limitMinutes;       // 0 = no limit
        long combinedTodayMs;
        boolean notified;
        String error;            // null when everything worked
    }

    /** Used by the background job: reads usage itself, then syncs. */
    static Summary syncInBackground(Context c) {
        Summary s = new Summary();
        try {
            if (!Config.syncConfigured(c)) { s.error = "Sync is not set up."; return s; }
            String pkg = Usage.targetPackage(c);
            if (pkg == null) { s.error = "No app selected."; return s; }
            if (!Usage.hasAccess(c)) { s.error = "Usage access is off."; return s; }
            long[] usage = Usage.foregroundMs(c, pkg);
            if (usage == null) { s.error = "Could not read usage data."; return s; }
            return sync(c, pkg, usage);
        } catch (Exception e) {
            s.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return s;
        }
    }

    /** Upload {today, week} for this device, then read everyone's numbers and the limit. */
    static Summary sync(Context c, String pkg, long[] usage) {
        Summary s = new Summary();
        try {
            Firestore fs = new Firestore(Config.get(c, Config.PROJECT), Config.get(c, Config.API_KEY));
            String code = Config.get(c, Config.CODE);
            String myId = Config.deviceId(c);
            String today = Usage.today();
            String appName = Usage.label(c, pkg);

            // 1. Upload this device's numbers
            Map<String, Object> mine = new LinkedHashMap<>();
            mine.put("id", myId);
            mine.put("type", Usage.isTv(c) ? "tv" : "mobile");
            mine.put("name", Build.MODEL == null ? "device" : Build.MODEL);
            mine.put("app", appName);
            mine.put("day", today);
            mine.put("todayMs", usage[0]);
            mine.put("weekMs", usage[1]);
            mine.put("updatedAt", System.currentTimeMillis());
            fs.write("sync/" + code + "/devices/" + myId, mine);

            // 2. Read every device (including this one) and add up today's time
            JSONArray docs = fs.listFields("sync/" + code + "/devices");
            for (int i = 0; i < docs.length(); i++) {
                JSONObject f = docs.getJSONObject(i);
                Row r = new Row();
                r.type = Firestore.str(f, "type");
                r.name = Firestore.str(f, "name");
                r.app = Firestore.str(f, "app");
                r.weekMs = Firestore.num(f, "weekMs", 0);
                r.updatedAt = Firestore.num(f, "updatedAt", 0);
                // Ignore "today" values that were uploaded on a previous day
                r.todayMs = today.equals(Firestore.str(f, "day")) ? Firestore.num(f, "todayMs", 0) : 0;
                r.me = myId.equals(Firestore.str(f, "id"));
                s.combinedTodayMs += r.todayMs;
                s.devices.add(r);
            }

            // 3. Read the shared limit and alert state
            JSONObject shared = fs.getFields("sync/" + code);
            s.limitMinutes = Math.max(0, Firestore.num(shared, "limitMinutes", 0));
            String alertDay = Firestore.str(shared, "alertDay");
            String alertText = Firestore.str(shared, "alertText");

            // 4. Limit reached for the first time today? Record the alert for every device.
            if (s.limitMinutes > 0 && s.combinedTodayMs >= s.limitMinutes * 60_000L
                    && !today.equals(alertDay)) {
                alertText = appName + ": " + Usage.format(s.combinedTodayMs)
                        + " used today across your devices (limit " + Usage.format(s.limitMinutes * 60_000L) + ").";
                Map<String, Object> alert = new LinkedHashMap<>();
                alert.put("alertDay", today);
                alert.put("alertText", alertText);
                alert.put("alertBy", (String) mine.get("type"));
                fs.write("sync/" + code, alert);
                alertDay = today;
            }

            // 5. Alert exists for today and this device has not shown it yet? Notify.
            if (today.equals(alertDay) && !today.equals(Config.get(c, Config.NOTIFIED_DAY))) {
                Notifier.showLimitReached(c, alertText.isEmpty() ? "Your daily usage limit was reached." : alertText);
                Config.set(c, Config.NOTIFIED_DAY, today);
                s.notified = true;
            }
        } catch (Exception e) {
            s.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return s;
    }

    /** Saves the shared daily limit (0 removes it). */
    static String saveLimit(Context c, long minutes) {
        try {
            Firestore fs = new Firestore(Config.get(c, Config.PROJECT), Config.get(c, Config.API_KEY));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("limitMinutes", minutes);
            fs.write("sync/" + Config.get(c, Config.CODE), m);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }
}
