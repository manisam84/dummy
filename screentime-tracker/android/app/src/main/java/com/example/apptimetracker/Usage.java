package com.example.apptimetracker;

import android.app.AppOpsManager;
import android.app.UiModeManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Process;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads app usage from Android and helps with device/app basics. Shared by the screen and the background job. */
final class Usage {
    private Usage() { }

    static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        return m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    static boolean hasAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            // Some devices report MODE_DEFAULT even when the user has enabled access
            return c.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Saved choice, else YouTube if installed, else null. */
    static String targetPackage(Context c) {
        String saved = Config.get(c, Config.TARGET_PKG);
        if (!saved.isEmpty()) return saved;
        String[] candidates = isTv(c)
                ? new String[]{"com.google.android.youtube.tv", "com.google.android.youtube"}
                : new String[]{"com.google.android.youtube"};
        for (String p : candidates) {
            try {
                c.getPackageManager().getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /**
     * Exact foreground time for each package, computed from foreground/background events
     * in a single pass over the last 7 days.
     *
     * @return package -> {todayMs, weekMs}, or null if usage data could not be read
     */
    @SuppressWarnings("deprecation")
    static Map<String, long[]> foregroundMs(Context c, Collection<String> pkgs) {
        long now = System.currentTimeMillis();
        long dayStart = startOfDay(0);
        long weekStart = startOfDay(6);

        Map<String, long[]> out = new LinkedHashMap<>();
        for (String p : pkgs) out.put(p, new long[2]);
        if (out.isEmpty()) return out;

        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(weekStart, now);
            if (events == null) return null;

            Map<String, Long> start = new HashMap<>();
            Set<String> seen = new HashSet<>();
            UsageEvents.Event e = new UsageEvents.Event();

            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                String pkg = e.getPackageName();
                long[] totals = out.get(pkg);
                if (totals == null) continue;
                int type = e.getEventType();
                long ts = e.getTimeStamp();
                Long st = start.get(pkg);

                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (st == null) start.put(pkg, ts);
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    // First event is "went to background": it was already open when the window began.
                    if (st == null && !seen.contains(pkg)) st = weekStart;
                    if (st != null) {
                        add(totals, st, ts, dayStart);
                        start.remove(pkg);
                    }
                } else {
                    continue;
                }
                seen.add(pkg);
            }
            for (Map.Entry<String, Long> en : start.entrySet()) {
                add(out.get(en.getKey()), en.getValue(), now, dayStart); // still open right now
            }
            return out;
        } catch (SecurityException ex) {
            return null;
        }
    }

    private static void add(long[] totals, long start, long end, long dayStart) {
        if (end <= start) return;
        totals[1] += end - start;
        totals[0] += Math.max(0, end - Math.max(start, dayStart));
    }

    static long startOfDay(int daysAgo) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return c.getTimeInMillis();
    }

    /** Today's date as text, e.g. 2026-11-07. Used to ignore yesterday's numbers. */
    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static String format(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }
}
