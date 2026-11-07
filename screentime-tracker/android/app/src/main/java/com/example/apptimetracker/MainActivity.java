package com.example.apptimetracker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * Shows how much time the tracked apps (default: YouTube) were used on THIS device, labels the device
 * as TV or Mobile, and (optionally) shares the numbers with your other devices through Firebase.
 */
public class MainActivity extends Activity {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{4,64}");

    private boolean isTv;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView deviceView, appsView, noteView, timerView;
    private Button grantButton, updateButton, chooseButton, syncButton, startTimerButton, overlayButton;
    private EditText minutesField;

    // Counts the timer down once a second, only while this screen is visible
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refreshTimer();
            if (TimerManager.endTime(MainActivity.this) > 0) handler.postDelayed(this, 1000);
        }
    };

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
        handler.removeCallbacks(ticker);
        handler.post(ticker); // timer display
        if (Config.syncConfigured(this) && Usage.hasAccess(this)) {
            SyncJobService.ensureSchedule(this, Config.intervalMinutes(this));
        } else {
            SyncJobService.cancel(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
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
        appsView = text(18 * scale, "#FFFFFF", false);
        noteView = text(14 * scale, "#AAAAAA", false);

        TextView timerTitle = text(20 * scale, "#FFFFFF", true);
        timerTitle.setText("Timer");
        timerView = text(16 * scale, "#FFCC66", false);
        minutesField = field("Minutes (" + TimerManager.MIN_MINUTES + " to " + TimerManager.MAX_MINUTES + ")",
                "30", InputType.TYPE_CLASS_NUMBER);
        minutesField.setGravity(Gravity.CENTER);
        startTimerButton = button("Start timer", v -> startTimer());
        overlayButton = button("Allow display over other apps", v -> openOverlaySettings());

        grantButton = button("Grant Usage access", v -> openUsageAccessSettings());
        updateButton = button("Update usage", v -> updateNow());
        chooseButton = button("Add / choose app", v -> showAppPicker());
        syncButton = button("Sync settings", v -> showSyncDialog());

        root.addView(deviceView);
        root.addView(updateButton);
        root.addView(grantButton);
        root.addView(appsView);
        root.addView(timerTitle);
        root.addView(minutesField);
        root.addView(startTimerButton);
        root.addView(timerView);
        root.addView(overlayButton);
        root.addView(chooseButton);
        root.addView(syncButton);
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

        if (!access) {
            appsView.setText("");
            noteView.setText("One-time step: tap \"Grant Usage access\", find \"App Time Tracker\" "
                    + "in the list and switch it on. Then come back.");
            return;
        }

        noteView.setText("Tap \"Update usage\" to refresh the numbers"
                + (Config.syncConfigured(this) ? " and sync with your other devices." : "."));
        runOnBackground(() -> {
            final List<SyncManager.TrackedApp> apps = SyncManager.trackedApps(this);
            final Map<String, long[]> usage = Usage.foregroundMs(this, packagesOf(apps));
            runOnUiThread(() -> showLocal(apps, usage));
        });
    }

    private static List<String> packagesOf(List<SyncManager.TrackedApp> apps) {
        List<String> pkgs = new ArrayList<>();
        for (SyncManager.TrackedApp a : apps) pkgs.add(a.pkg);
        return pkgs;
    }

    private void showLocal(List<SyncManager.TrackedApp> apps, Map<String, long[]> usage) {
        if (isFinishing()) return;
        if (apps.isEmpty()) {
            appsView.setText("No app selected");
            noteView.setText("Tap \"Add / choose app\" to pick the app you want to track.");
            return;
        }
        if (usage == null) {
            appsView.setText("");
            noteView.setText("Could not read usage data. Check that Usage access is on.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (SyncManager.TrackedApp a : apps) {
            long[] u = usage.get(a.pkg);
            if (u == null) u = new long[2];
            sb.append(a.name).append("\nToday: ").append(Usage.format(u[0]))
                    .append("   •   Last 7 days: ").append(Usage.format(u[1])).append("\n\n");
        }
        appsView.setText(sb.toString().trim());
    }

    /** The "Update usage" button: re-read this device, and sync if Firebase is set up. */
    private void updateNow() {
        if (!Usage.hasAccess(this)) { refreshLocal(); return; }

        updateButton.setEnabled(false);
        noteView.setText("Updating…");
        runOnBackground(() -> {
            final SyncManager.Summary summary = Config.syncConfigured(this) ? SyncManager.sync(this) : null;
            final List<SyncManager.TrackedApp> apps = SyncManager.trackedApps(this);
            // Without a successful sync, show at least this device's own numbers
            final Map<String, long[]> usage = (summary == null || summary.error != null)
                    ? Usage.foregroundMs(this, packagesOf(apps)) : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                updateButton.setEnabled(true);
                if (summary == null) {
                    showLocal(apps, usage == null ? Collections.<String, long[]>emptyMap() : usage);
                    noteView.setText("Updated. Tap \"Sync settings\" to share with your other devices.");
                } else if (summary.error != null) {
                    showLocal(apps, usage);
                    noteView.setText("Sync problem: " + summary.error);
                } else {
                    showSummary(summary);
                    SyncJobService.ensureSchedule(this, summary.intervalMinutes);
                }
            });
        });
    }

    private void showSummary(SyncManager.Summary s) {
        if (s.apps.isEmpty()) {
            appsView.setText("No apps in Firebase yet.");
            noteView.setText("Tap \"Add / choose app\", or add one in the Firebase console.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (SyncManager.AppResult a : s.apps) {
            sb.append(a.name).append("\n");
            for (SyncManager.DeviceRow r : a.rows) {
                long mins = Math.max(0, (now - r.updatedAt) / 60_000);
                sb.append("tv".equals(r.type) ? "📺 " : "📱 ").append(r.deviceName)
                        .append(r.me ? " (this device)" : "").append(": ")
                        .append(Usage.format(r.todayMs)).append(" today, ")
                        .append(Usage.format(r.weekMs)).append(" this week")
                        .append(r.me ? "" : " (updated " + (mins < 1 ? "just now" : mins + " min ago") + ")")
                        .append("\n");
            }
            sb.append("Combined today: ").append(Usage.format(a.combinedTodayMs));
            if (a.limitMinutes > 0) {
                long limitMs = a.limitMinutes * 60_000L;
                sb.append(" of ").append(Usage.format(limitMs)).append(" limit (")
                        .append(a.combinedTodayMs * 100 / limitMs).append("%)");
            } else {
                sb.append(" (no limit set)");
            }
            sb.append("\n\n");
        }
        appsView.setText(sb.toString().trim());
        noteView.setText((s.notifications > 0 ? "Notification shown. " : "Synced. ")
                + "Background check every " + s.intervalMinutes + " min"
                + (s.warnPercent > 0 ? ", warning at " + s.warnPercent + "%." : "."));
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
                    onResume(); // reschedule the background job
                    noteView.setText(Config.syncConfigured(this)
                            ? "Saved. Tap \"Update usage\" to sync."
                            : "Fill in all three fields to turn on sync.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -------------------------------------------------------------- Timer

    /** Whether the app may show things over other apps (needed for the "Time is up" screen). */
    private boolean canShowOverApps() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void startTimer() {
        if (TimerManager.endTime(this) > 0) return; // one timer at a time; it cannot be stopped

        int minutes;
        try {
            minutes = Integer.parseInt(minutesField.getText().toString().trim());
        } catch (NumberFormatException e) {
            minutes = -1;
        }
        if (minutes < TimerManager.MIN_MINUTES || minutes > TimerManager.MAX_MINUTES) {
            timerView.setText("Enter minutes from " + TimerManager.MIN_MINUTES + " to "
                    + TimerManager.MAX_MINUTES + ".");
            return;
        }

        boolean exact = TimerManager.start(this, minutes);
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        noteView.setText("Timer started for " + minutes + " min. It cannot be stopped."
                + (exact ? "" : " This device may end it a little late.")
                + (canShowOverApps() ? ""
                : " Allow \"Display over other apps\" so the \"Time is up\" screen can appear."));
    }

    /** Updates the timer text and buttons from the saved end time. */
    private void refreshTimer() {
        overlayButton.setVisibility(canShowOverApps() ? View.GONE : View.VISIBLE);

        long end = TimerManager.endTime(this);
        long now = System.currentTimeMillis();
        boolean running = end > now;
        startTimerButton.setEnabled(!running && end <= 0);
        minutesField.setEnabled(!running && end <= 0);

        if (running) {
            timerView.setText("Time left: " + clock(end - now) + "   (ends "
                    + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(end)) + ")");
        } else if (end > 0 && TimerManager.wasMissed(this)) {
            TimerManager.clear(this);
            timerView.setText("The last timer was missed: the app was force-closed or the device restarted.");
        } else if (end > 0) {
            timerView.setText("Time is up…"); // the alarm is about to arrive
        } else {
            timerView.setText("No timer running.");
        }
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000, h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.US, "%02d:%02d", m, sec);
    }

    /** Opens the "Display over other apps" screen; some TVs have none, so explain instead of crashing. */
    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            noteView.setText("This device has no \"Display over other apps\" screen. On a computer, run:\n"
                    + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        }
    }

    // ------------------------------------------------------ App selection

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        // list text -> {app name, package}
        final Map<String, String[]> apps = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                String name = ri.loadLabel(pm).toString();
                apps.put(name + "  (" + pkg + ")", new String[]{name, pkg});
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
                    final String[] picked = apps.get(labels.get(which));
                    Config.set(this, Config.TARGET_PKG, picked[1]);
                    if (!Config.syncConfigured(this)) {
                        refreshLocal();
                        return;
                    }
                    noteView.setText("Adding to Firebase…");
                    runOnBackground(() -> {
                        final String problem = SyncManager.addApp(this, picked[0], picked[1]);
                        runOnUiThread(() -> {
                            if (isFinishing()) return;
                            if (problem != null) noteView.setText(problem);
                            else updateNow();
                        });
                    });
                })
                .show();
    }
}
