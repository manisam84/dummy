package com.example.apptimetracker;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Runs about every 15 minutes in the background to upload usage and check the limit. */
public class SyncJobService extends JobService {
    private static final int JOB_ID = 42;

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                SyncManager.syncInBackground(app);
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true; // work continues on the thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // try again later
    }

    /** Starts the repeating check unless it is already scheduled. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_ID) return;
        }
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJobService.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }
}
