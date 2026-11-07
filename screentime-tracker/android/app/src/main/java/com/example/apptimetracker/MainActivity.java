package com.example.apptimetracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.UiModeManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows how much time a chosen app (default: YouTube) was used on THIS device,
 * and labels the device as TV or Mobile.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "prefs";
    private static final String KEY_PKG = "target_pkg";
    private static final long AUTO_REFRESH_MS = 30_000;

    private boolean isTv;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView deviceView, appView, todayView, weekView, noteView;
    private Button actionButton, chooseButton;

    private final Runnable autoRefresh = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        UiModeManager uiMode = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        isTv = uiMode != null
                && uiMode.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(autoRefresh); // refreshes now (also after returning from Settings), then every 30s
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        float scale = isTv ? 1.6f : 1f;
        int pad = dp(24 * scale);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#0F0F0F"));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        deviceView = text(18 * scale, "#FF3355", true);
        appView = text(30 * scale, "#FFFFFF", true);
        todayView = text(26 * scale, "#FFFFFF", false);
        weekView = text(26 * scale, "#FFFFFF", false);
        noteView = text(14 * scale, "#AAAAAA", false);

        actionButton = new Button(this);
        actionButton.setText("Grant Usage access");
        actionButton.setOnClickListener(v -> openUsageAccessSettings());

        chooseButton = new Button(this);
        chooseButton.setText("Choose app");
        chooseButton.setOnClickListener(v -> showAppPicker());

        root.addView(deviceView);
        root.addView(appView);
        root.addView(todayView);
        root.addView(weekView);
        root.addView(actionButton);
        root.addView(chooseButton);
        root.addView(noteView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView text(float sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(6));
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    /** Opens the Usage access screen; some TVs don't have one, so explain instead of crashing. */
    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException e) {
            noteView.setText("This device has no Usage access screen. On a computer, run:\n"
                    + "adb shell appops set " + getPackageName() + " GET_USAGE_STATS allow");
        }
    }

    // -------------------------------------------------------------- Logic

    private void refresh() {
        deviceView.setText(isTv ? "📺 Running on: TV" : "📱 Running on: Mobile");

        final boolean access = hasUsageAccess();
        actionButton.setVisibility(access ? View.GONE : View.VISIBLE);
        // First-run flow: focus the required step; afterwards focus "Choose app" (matters for TV remotes)
        (access ? chooseButton : actionButton).requestFocus();

        final String pkg = getTargetPackage();
        if (pkg == null) {
            appView.setText("No app selected");
            todayView.setText("");
            weekView.setText("");
            noteView.setText("Tap \"Choose app\" to pick the app you want to track.");
            return;
        }
        appView.setText(labelFor(pkg));

        if (!access) {
            todayView.setText("");
            weekView.setText("");
            noteView.setText("One-time step: tap the button, find \"App Time Tracker\" "
                    + "in the list and switch it on. Then come back.");
            return;
        }

        final long now = System.currentTimeMillis();
        final long dayStart = startOfDay(0);
        final long weekStart = startOfDay(6);
        // Reading usage events can be slow on devices with a lot of history, so do it off the UI thread.
        try {
            io.execute(() -> {
                final long[] result = foregroundMs(pkg, weekStart, dayStart, now);
                runOnUiThread(() -> {
                    if (isFinishing() || !pkg.equals(getTargetPackage())) return;
                    if (result == null) {
                        todayView.setText("");
                        weekView.setText("");
                        noteView.setText("Could not read usage data. Check that Usage access is on.");
                        return;
                    }
                    todayView.setText("Today: " + format(result[0]));
                    weekView.setText("Last 7 days: " + format(result[1]));
                    noteView.setText("Time is read from Android's own usage data on this "
                            + (isTv ? "TV" : "phone") + ". Updates every 30 seconds.");
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            // Some devices report MODE_DEFAULT even when the user has enabled access
            return checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Exact foreground time for pkg, computed from foreground/background events.
     * One pass over the last 7 days gives both figures.
     *
     * @return {todayMs, weekMs}, or null if usage data could not be read
     */
    @SuppressWarnings("deprecation")
    private long[] foregroundMs(String pkg, long weekStart, long dayStart, long now) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(weekStart, now);
            if (events == null) return null;

            UsageEvents.Event e = new UsageEvents.Event();
            long[] totals = new long[2]; // [today, week]
            long start = -1;
            boolean first = true;

            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (!pkg.equals(e.getPackageName())) continue;
                int type = e.getEventType();
                long ts = e.getTimeStamp();

                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (start < 0) start = ts;
                } else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    // If the first thing we see is "went to background", the app was
                    // already in the foreground when the window began.
                    if (start < 0 && first) start = weekStart;
                    if (start >= 0) {
                        addInterval(totals, start, ts, dayStart);
                        start = -1;
                    }
                } else {
                    continue;
                }
                first = false;
            }
            if (start >= 0) addInterval(totals, start, now, dayStart); // still open right now
            return totals;
        } catch (SecurityException ex) {
            return null;
        }
    }

    private static void addInterval(long[] totals, long start, long end, long dayStart) {
        if (end <= start) return;
        totals[1] += end - start;
        totals[0] += Math.max(0, end - Math.max(start, dayStart));
    }

    private long startOfDay(int daysAgo) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return c.getTimeInMillis();
    }

    private static String format(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    // ------------------------------------------------------ App selection

    /** Saved choice, else YouTube if installed, else null. */
    private String getTargetPackage() {
        String saved = prefs.getString(KEY_PKG, null);
        if (saved != null) return saved;
        String[] candidates = isTv
                ? new String[]{"com.google.android.youtube.tv", "com.google.android.youtube"}
                : new String[]{"com.google.android.youtube"};
        for (String c : candidates) {
            try {
                getPackageManager().getPackageInfo(c, 0);
                return c;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    private String labelFor(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        final Map<String, String> apps = new LinkedHashMap<>(); // label -> package
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                apps.put(ri.loadLabel(pm).toString() + "  (" + pkg + ")", pkg);
            }
        }
        final List<String> labels = new ArrayList<>(apps.keySet());
        Collections.sort(labels, String.CASE_INSENSITIVE_ORDER);
        if (labels.isEmpty()) {
            noteView.setText("No launchable apps found.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Track which app?")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    prefs.edit().putString(KEY_PKG, apps.get(labels.get(which))).apply();
                    refresh();
                })
                .show();
    }
}
