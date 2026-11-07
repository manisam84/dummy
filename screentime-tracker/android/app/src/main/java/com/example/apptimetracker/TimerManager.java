package com.example.apptimetracker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * The countdown timer. It lives only on this device (nothing goes to Firebase).
 * The end time is saved in the settings, and an alarm tells {@link TimerReceiver} when the time is up.
 */
final class TimerManager {
    private TimerManager() { }

    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 1440;
    static final String ACTION_END = "com.example.apptimetracker.TIMER_END";

    /** After the end time, wait this long for the alarm to arrive before calling the timer "missed". */
    private static final long MISSED_GRACE_MS = 120_000L;

    /** End time in milliseconds, or 0 when no timer is saved. */
    static long endTime(Context c) {
        try {
            return Long.parseLong(Config.get(c, Config.TIMER_END));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean isRunning(Context c) {
        return endTime(c) > System.currentTimeMillis();
    }

    /** True when a timer was saved, its time is long past, and the alarm never cleared it. */
    static boolean wasMissed(Context c) {
        long end = endTime(c);
        return end > 0 && System.currentTimeMillis() > end + MISSED_GRACE_MS;
    }

    static void clear(Context c) {
        Config.set(c, Config.TIMER_END, "");
    }

    /**
     * Starts a timer.
     *
     * @return true if the alarm is exact; false if this device only allowed an approximate alarm,
     *         so the timer may end a little late
     */
    static boolean start(Context c, int minutes) {
        long end = System.currentTimeMillis() + minutes * 60_000L;
        Config.set(c, Config.TIMER_END, String.valueOf(end));

        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent whenDone = PendingIntent.getBroadcast(c, 7,
                new Intent(c, TimerReceiver.class).setAction(ACTION_END), flags());
        PendingIntent showApp = PendingIntent.getActivity(c, 8, new Intent(c, MainActivity.class), flags());
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(end, showApp), whenDone);
            return true;
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, end, whenDone); // approximate: may be a little late
            return false;
        }
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }
}
