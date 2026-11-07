package com.example.apptimetracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.UiModeManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
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

/**
 * Shows how much time a chosen app (default: YouTube) was used on THIS device,
 * and labels the device as TV or Mobile.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "prefs";
    private static final String KEY_PKG = "target_pkg";

    private boolean isTv;
    private SharedPreferences prefs;

    private TextView deviceView, appView, todayView, weekView, noteView;
    private Button actionButton, chooseButton;

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
        refresh(); // also runs when coming back from the Usage access screen
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        float scale = isTv ? 1.6f : 1f;
        int pad = (int) (24 * getResources().getDisplayMetrics().density * scale);

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
        actionButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

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
        chooseButton.requestFocus(); // so TV remotes have a starting focus
    }

    private TextView text(float sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 12, 0, 12);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    // -------------------------------------------------------------- Logic

    private void refresh() {
        deviceView.setText(isTv ? "📺 Running on: TV" : "📱 Running on: Mobile");

        String pkg = getTargetPackage();
        if (pkg == null) {
            appView.setText("No app selected");
            todayView.setText("");
            weekView.setText("");
            noteView.setText("Tap \"Choose app\" to pick the app you want to track.");
            actionButton.setVisibility(hasUsageAccess() ? View.GONE : View.VISIBLE);
            return;
        }
        appView.setText(labelFor(pkg));

        if (!hasUsageAccess()) {
            todayView.setText("");
            weekView.setText("");
            noteView.setText("One-time step: tap the button, find \"App Time Tracker\" "
                    + "in the list and switch it on. Then come back.");
            actionButton.setVisibility(View.VISIBLE);
            return;
        }

        actionButton.setVisibility(View.GONE);
        long now = System.currentTimeMillis();
        todayView.setText("Today: " + format(foregroundMs(pkg, startOfDay(0), now)));
        weekView.setText("Last 7 days: " + format(foregroundMs(pkg, startOfDay(6), now)));
        noteView.setText("Time is read from Android's own usage data on this "
                + (isTv ? "TV" : "phone") + ".");
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private long foregroundMs(String pkg, long from, long to) {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(from, to);
        UsageStats s = stats == null ? null : stats.get(pkg);
        return s == null ? 0 : s.getTotalTimeInForeground();
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
