package com.vortex.timelock;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/**
 * Execution point of the platform work routine (BUILD RECIPE 5).
 *
 * One cheap, synchronous reconciliation per run: no thread, no Handler, no loop
 * and no PowerManager wake lock is ever created here.
 *
 * Returning false from {@link #onStartJob} tells JobScheduler that the work is
 * already complete, so the job's execution slot — and with it the implicit
 * wakelock the platform holds while a job runs — is released at once. The
 * platform only ever delivers this job under the low-power constraints declared
 * in {@link EnforcerScheduler}, so the run itself is effectively free.
 */
public class EnforcerJobService extends JobService {

    static final String TAG = "TL.Job";

    @Override
    public boolean onStartJob(JobParameters params) {
        final int id = params == null ? -1 : params.getJobId();
        try {
            Log.i(TAG, "reconcile from job " + id);
            Engine.selfHeal(this);
            Engine.reevaluate(this, "job:" + id);
        } catch (Throwable t) {
            Log.w(TAG, "job " + id + " failed", t);
        }
        // No deferred work: the slot is released and no wakelock is retained.
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Pre-empted by the platform (constraint no longer met) -> reschedule.
        return true;
    }
}
