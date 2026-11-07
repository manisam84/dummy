package com.example.apptimetracker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** Small wrapper around SharedPreferences for all saved settings. */
final class Config {
    private Config() { }

    static final String PREFS = "prefs";
    static final String TARGET_PKG = "target_pkg";
    static final String PROJECT = "fb_project";
    static final String API_KEY = "fb_key";
    static final String CODE = "fb_code";
    static final String DEVICE_ID = "device_id";
    static final String NOTIFIED_DAY = "notified_day";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String get(Context c, String key) {
        return prefs(c).getString(key, "");
    }

    static void set(Context c, String key, String value) {
        prefs(c).edit().putString(key, value).apply();
    }

    /** True when the Firebase project, API key and pairing code are all filled in. */
    static boolean syncConfigured(Context c) {
        return !get(c, PROJECT).isEmpty() && !get(c, API_KEY).isEmpty() && !get(c, CODE).isEmpty();
    }

    /** A random id that identifies this device in the shared data. */
    static String deviceId(Context c) {
        String id = get(c, DEVICE_ID);
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().replace("-", "");
            set(c, DEVICE_ID, id);
        }
        return id;
    }
}
