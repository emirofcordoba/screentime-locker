package com.vortex.timelock;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Platform work routine (BUILD RECIPE 5).
 *
 * This is the replacement for EVERY custom background loop, thread and
 * continuous Handler the engine used to run. JobScheduler is the platform's own
 * work scheduler — it is the mechanism AndroidX WorkManager itself is built on
 * above API 22 — so it is used directly and the build stays dependency-free
 * (no AndroidX, no AGP, no Gradle).
 *
 * Two jobs are registered. Both are pinned to the cheapest states the platform
 * can offer, so neither can cost meaningful battery:
 *
 *   JOB_RECONCILE  device idle + battery not low + storage not low + NO network
 *                  -> only ever runs inside a doze maintenance window, i.e. when
 *                     the SoC is already awake for reasons of its own.
 *   JOB_CHARGING   charging + battery not low + storage not low + UNMETERED net
 *                  -> only ever runs on external power with a free (wi-fi)
 *                     network, i.e. the two conditions under which background
 *                     work is free.
 *
 * Neither job holds a wake lock: {@link EnforcerJobService#onStartJob} does one
 * cheap synchronous reconciliation and returns false, so JobScheduler releases
 * the execution slot (and therefore the implicit wakelock) immediately. There is
 * no partial wake lock, no alarm-driven wakeup and no periodic in-process timer
 * anywhere in this design.
 *
 * The jobs are pure redundancy / self-healing. Real-time enforcement is timed by
 * passive (NON-WAKEUP) AlarmManager triggers in {@link EnforcerReceiver}.
 */
final class EnforcerScheduler {

    static final String TAG = "TL.Sched";

    static final int JOB_RECONCILE = 41100;
    static final int JOB_CHARGING = 41101;

    /** JobScheduler's own floor for periodic work. */
    static final long PERIOD_MS = 15L * 60_000L;

    private EnforcerScheduler() {}

    /** Idempotently register the low-power work routine. */
    static void ensure(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            if (js.getPendingJob(JOB_RECONCILE) == null) {
                JobInfo idle = new JobInfo.Builder(JOB_RECONCILE,
                        new ComponentName(c, EnforcerJobService.class))
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                        .setPersisted(false)
                        .setPeriodic(PERIOD_MS)
                        .build();
                js.schedule(idle);
                Log.i(TAG, "scheduled reconcile job (idle/battery/storage/no-network)");
            }
            if (js.getPendingJob(JOB_CHARGING) == null) {
                JobInfo charging = new JobInfo.Builder(JOB_CHARGING,
                        new ComponentName(c, EnforcerJobService.class))
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                        .setPersisted(false)
                        .setPeriodic(PERIOD_MS)
                        .build();
                js.schedule(charging);
                Log.i(TAG, "scheduled charging job (charging/battery/storage/unmetered)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed", t);
        }
    }

    /** Remove the work routine entirely (used when the app is idle/unconfigured). */
    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        try {
            js.cancel(JOB_RECONCILE);
            js.cancel(JOB_CHARGING);
        } catch (Throwable ignored) {}
    }
}
