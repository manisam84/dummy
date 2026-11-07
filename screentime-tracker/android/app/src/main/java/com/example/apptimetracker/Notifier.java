package com.example.apptimetracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Shows the "warning", "limit reached" and "Time is up" notifications. */
final class Notifier {
    private Notifier() { }

    private static final String CHANNEL = "limit_alerts";
    private static final int TIME_UP_ID = 2001;

    /** @param id a number that is the same for the same app + kind, so alerts for different apps don't replace each other */
    static void show(Context c, int id, String title, String text) {
        post(c, id, title, text, null);
    }

    /** "Time is up" notification. It also opens the full-screen message when the device is locked or idle. */
    static void showTimeUp(Context c) {
        PendingIntent screen = PendingIntent.getActivity(c, 1,
                new Intent(c, TimeUpActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags());
        post(c, TIME_UP_ID, "Time is up", "Your timer has ended. Tap to close.", screen);
    }

    static void cancelTimeUp(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(TIME_UP_ID);
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @SuppressWarnings("deprecation")
    private static void post(Context c, int id, String title, String text, PendingIntent fullScreen) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Usage limit alerts", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH);
        }

        PendingIntent open = fullScreen != null ? fullScreen
                : PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags());

        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        if (fullScreen != null) {
            b.setFullScreenIntent(fullScreen, true).setCategory(Notification.CATEGORY_ALARM);
        }
        try {
            nm.notify(id, b.build());
        } catch (SecurityException ignored) {
            // Notification permission not granted; nothing else we can do here.
        }
    }
}
