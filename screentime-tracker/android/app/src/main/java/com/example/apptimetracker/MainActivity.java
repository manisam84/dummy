package com.example.apptimetracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * Shows how much time a chosen app (default: YouTube) was used on THIS device,
 * labels the device as TV or Mobile, and (optionally) shares the numbers with
 * your other devices through Firebase.
 */
public class MainActivity extends Activity {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{4,64}");

    private boolean isTv;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView deviceView, appView, todayView, weekView, othersView, limitView, noteView;
    private Button grantButton, updateButton, chooseButton, syncButton, limitButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = Usage.isTv(this);
        buildUi();

        // Android 13+ needs permission before notifications can be shown
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLocal(); // just this device, no network; also runs when coming back from Settings
        if (Config.syncConfigured(this) && Usage.hasAccess(this)) {
            SyncJobService.schedule(this); // background check about every 15 minutes
        } else {
            SyncJobService.cancel(this);
        }
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
        othersView = text(16 * scale, "#DDDDDD", false);
        limitView = text(16 * scale, "#FFCC66", false);
        noteView = text(14 * scale, "#AAAAAA", false);

        grantButton = button("Grant Usage access", v -> openUsageAccessSettings());
        updateButton = button("Update usage", v -> updateNow());
        chooseButton = button("Choose app", v -> showAppPicker());
        syncButton = button("Sync settings", v -> showSyncDialog());
        limitButton = button("Set daily limit", v -> showLimitDialog());

        root.addView(deviceView);
        root.addView(appView);
        root.addView(todayView);
        root.addView(weekView);
        root.addView(updateButton);
        root.addView(grantButton);
        root.addView(othersView);
        root.addView(limitView);
        root.addView(limitButton);
        root.addView(syncButton);
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

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        return b;
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

    /** Shows this device's numbers only (no network). Runs when the screen opens. */
    private void refreshLocal() {
        deviceView.setText(isTv ? "📺 Running on: TV" : "📱 Running on: Mobile");

        final boolean access = Usage.hasAccess(this);
        grantButton.setVisibility(access ? View.GONE : View.VISIBLE);
        updateButton.setEnabled(access);
        // First-run flow: focus the required step; afterwards "Update usage" (matters for TV remotes)
        (access ? updateButton : grantButton).requestFocus();

        final String pkg = Usage.targetPackage(this);
        if (pkg == null) {
            appView.setText("No app selected");
            todayView.setText("");
            weekView.setText("");
            noteView.setText("Tap \"Choose app\" to pick the app you want to track.");
            return;
        }
        appView.setText(Usage.label(this, pkg));

        if (!access) {
            todayView.setText("");
            weekView.setText("");
            noteView.setText("One-time step: tap \"Grant Usage access\", find \"App Time Tracker\" "
                    + "in the list and switch it on. Then come back.");
            return;
        }

        noteView.setText("Tap \"Update usage\" to refresh the numbers"
                + (Config.syncConfigured(this) ? " and sync with your other devices." : "."));
        runOnBackground(() -> {
            final long[] usage = Usage.foregroundMs(this, pkg);
            runOnUiThread(() -> showLocal(pkg, usage));
        });
    }

    private void showLocal(String pkg, long[] usage) {
        if (isFinishing() || !pkg.equals(Usage.targetPackage(this))) return;
        if (usage == null) {
            todayView.setText("");
            weekView.setText("");
            noteView.setText("Could not read usage data. Check that Usage access is on.");
            return;
        }
        todayView.setText("Today: " + Usage.format(usage[0]));
        weekView.setText("Last 7 days: " + Usage.format(usage[1]));
    }

    /** The "Update usage" button: re-read this device, and sync if Firebase is set up. */
    private void updateNow() {
        final String pkg = Usage.targetPackage(this);
        if (pkg == null || !Usage.hasAccess(this)) { refreshLocal(); return; }

        updateButton.setEnabled(false);
        noteView.setText("Updating…");
        runOnBackground(() -> {
            final long[] usage = Usage.foregroundMs(this, pkg);
            final SyncManager.Summary summary = (usage != null && Config.syncConfigured(this))
                    ? SyncManager.sync(this, pkg, usage) : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                updateButton.setEnabled(true);
                showLocal(pkg, usage);
                if (usage == null) return;
                if (summary == null) {
                    noteView.setText("Updated. Tap \"Sync settings\" to share with your other devices.");
                } else {
                    showSummary(summary);
                }
            });
        });
    }

    private void showSummary(SyncManager.Summary s) {
        if (s.error != null) {
            noteView.setText("Sync problem: " + s.error);
            return;
        }
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (SyncManager.Row r : s.devices) {
            long mins = Math.max(0, (now - r.updatedAt) / 60_000);
            sb.append("tv".equals(r.type) ? "📺 TV" : "📱 Mobile").append(" – ").append(r.name)
                    .append(r.me ? " (this device)" : "").append("\n")
                    .append(r.app).append(": ").append(Usage.format(r.todayMs)).append(" today, ")
                    .append(Usage.format(r.weekMs)).append(" this week")
                    .append(r.me ? "" : " (updated " + (mins < 1 ? "just now" : mins + " min ago") + ")")
                    .append("\n\n");
        }
        sb.append("Combined today: ").append(Usage.format(s.combinedTodayMs));
        othersView.setText(sb.toString());

        limitView.setText(s.limitMinutes > 0
                ? "Daily limit (all devices combined): " + Usage.format(s.limitMinutes * 60_000L)
                : "No daily limit set.");
        noteView.setText(s.notified ? "Limit reached – notification sent." : "Synced.");
    }

    private void runOnBackground(Runnable r) {
        try {
            io.execute(r);
        } catch (RejectedExecutionException ignored) { }
    }

    // ------------------------------------------------------------ Dialogs

    private EditText field(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(inputType);
        return e;
    }

    private void showSyncDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, p / 2, p, 0);

        final EditText project = field("Firebase project ID", Config.get(this, Config.PROJECT),
                InputType.TYPE_CLASS_TEXT);
        final EditText key = field("Firebase Web API key", Config.get(this, Config.API_KEY),
                InputType.TYPE_CLASS_TEXT);
        final EditText code = field("Pairing code (same on every device)", Config.get(this, Config.CODE),
                InputType.TYPE_CLASS_TEXT);
        box.addView(project);
        box.addView(key);
        box.addView(code);

        new AlertDialog.Builder(this)
                .setTitle("Sync settings")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String c = code.getText().toString().trim();
                    if (!c.isEmpty() && !CODE_PATTERN.matcher(c).matches()) {
                        noteView.setText("Pairing code must be 4–64 letters, numbers, - or _.");
                        return;
                    }
                    Config.set(this, Config.PROJECT, project.getText().toString().trim());
                    Config.set(this, Config.API_KEY, key.getText().toString().trim());
                    Config.set(this, Config.CODE, c);
                    onResume(); // reschedule background job
                    noteView.setText(Config.syncConfigured(this)
                            ? "Saved. Tap \"Update usage\" to sync."
                            : "Fill in all three fields to turn on sync.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLimitDialog() {
        if (!Config.syncConfigured(this)) {
            noteView.setText("Set up \"Sync settings\" first. The limit is stored in Firebase.");
            return;
        }
        final EditText minutes = field("Minutes per day, all devices combined (0 = no limit)", "",
                InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("Daily limit")
                .setView(minutes)
                .setPositiveButton("Save", (d, w) -> {
                    String t = minutes.getText().toString().trim();
                    if (t.isEmpty() || t.length() > 6) { noteView.setText("Enter a number of minutes."); return; }
                    final long m = Long.parseLong(t);
                    noteView.setText("Saving limit…");
                    runOnBackground(() -> {
                        final String err = SyncManager.saveLimit(this, m);
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            if (err != null) noteView.setText("Could not save limit: " + err);
                            else updateNow();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------ App selection

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
                    Config.set(this, Config.TARGET_PKG, apps.get(labels.get(which)));
                    refreshLocal();
                })
                .show();
    }
}
