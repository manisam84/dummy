package com.example.apptimetracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Called by the alarm when the timer ends. Shows the "Time is up" screen. */
public class TimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !TimerManager.ACTION_END.equals(intent.getAction())) return;

        TimerManager.clear(context);

        // Try to open the screen directly. Android may block this from the background
        // (it depends on the Android version and on "Display over other apps"), so also post
        // a notification that opens the same screen.
        try {
            context.startActivity(new Intent(context, TimeUpActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
        Notifier.showTimeUp(context);
    }
}
