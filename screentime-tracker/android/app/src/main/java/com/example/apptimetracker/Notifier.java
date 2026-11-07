package com.example.apptimetracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Shows the "limit reached" notification. */
final class Notifier {
    private Notifier() { }

    private static final String CHANNEL = "limit_alerts";

    @SuppressWarnings("deprecation")
    static void showLimitReached(Context c, String text) {
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

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags);

        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Usage limit reached")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true);
        try {
            nm.notify(1001, b.build());
        } catch (SecurityException ignored) {
            // Notification permission not granted; nothing else we can do here.
        }
    }
}
