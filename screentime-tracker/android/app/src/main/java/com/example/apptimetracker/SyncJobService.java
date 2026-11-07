package com.example.apptimetracker;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Runs in the background to upload usage and check the limits. How often comes from Firebase
 * (checkIntervalMinutes, never below Android's 15 minute minimum).
 */
public class SyncJobService extends JobService {
    // Two ids so a changed interval can be scheduled before the old job is cancelled.
    private static final int ID_A = 42;
    private static final int ID_B = 43;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            SyncManager.Summary s = null;
            try {
                s = SyncManager.sync(app);
            } finally {
                jobFinished(params, false);
            }
            // Pick up a changed interval from Firebase
            if (s != null && s.error == null) ensureSchedule(app, s.intervalMinutes);
        }).start();
        return true; // work continues on the thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // try again later
    }

    /** Makes sure one repeating job exists with the given interval (in minutes, at least 15). */
    static void ensureSchedule(Context c, long minutes) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        long ms = Math.max(15, minutes) * 60_000L;

        int existing = -1;
        boolean same = false;
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == ID_A || j.getId() == ID_B) {
                existing = j.getId();
                same = j.isPeriodic() && j.getIntervalMillis() == ms;
            }
        }
        if (same) return;

        int newId = existing == ID_A ? ID_B : ID_A;
        js.schedule(new JobInfo.Builder(newId, new ComponentName(c, SyncJobService.class))
                .setPeriodic(ms)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
        if (existing != -1) js.cancel(existing);
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        js.cancel(ID_A);
        js.cancel(ID_B);
    }
}
