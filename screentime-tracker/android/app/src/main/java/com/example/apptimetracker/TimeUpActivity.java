package com.example.apptimetracker;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen "Time is up" message shown when the timer ends. Close or Back goes to the home screen.
 * It can be closed on purpose, so it can never lock you out of the device.
 */
public class TimeUpActivity extends Activity {

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over the lock screen and wake the screen
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float scale = Usage.isTv(this) ? 1.6f : 1f;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Time is up");
        title.setTextColor(Color.WHITE);
        title.setTextSize(44 * scale);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("Your timer has ended.");
        sub.setTextColor(Color.parseColor("#AAAAAA"));
        sub.setTextSize(20 * scale);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 16, 0, 48);

        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> goHome());

        root.addView(title);
        root.addView(sub);
        root.addView(close);
        setContentView(root);
        close.requestFocus(); // so a TV remote can press it straight away

        Notifier.cancelTimeUp(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goHome();
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
