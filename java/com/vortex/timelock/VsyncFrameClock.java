package com.vortex.timelock;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;

/**
 * VsyncFrameClock -- the hardware-synchronized frame clock.
 *
 * This is the ONLY legitimate meaning of "hardware-synchronized layout frames"
 * on Android: the work is executed on a real display VSYNC callback delivered by
 * {@link Choreographer#postFrameCallback}, so it runs in lock-step with the
 * compositor's refresh (SurfaceFlinger / HWComposer) instead of at an arbitrary
 * software instant. A frame callback is what the platform uses to synchronize
 * every layout/draw pass to the panel's refresh signal.
 *
 * ---------------------------------------------------------------------------
 * HONESTY NOTE (read this before believing any "zero-CPU" claim):
 *
 * A VSYNC callback is NOT a way to update the screen "without waking the main
 * CPU core". Choreographer.doFrame() executes ON the main thread and therefore
 * REQUIRES the CPU to be awake to run. No third-party Android API lets an app
 * change arbitrary on-screen text every second using only the display
 * controller: any change to an app-owned surface requires the SoC to build a
 * new frame. The single construct that genuinely renders a live second counter
 * with ZERO involvement of this process is the platform notification
 * chronometer, which SystemUI draws itself inside the shell process (see
 * {@link LiveCountdown}). VsyncFrameClock is the *corrected* fallback for the
 * case where that native path is unavailable, and it is deliberately parked
 * whenever the native path can do the job (see {@link CountdownTicker}).
 * ---------------------------------------------------------------------------
 *
 * TWO-PHASE SCHEDULING. Waiting several hundred milliseconds on a pure
 * Choreographer callback would require a frame callback on EVERY frame until the
 * boundary, i.e. 60-120 callbacks per second of a still screen -- strictly worse
 * than the software timer it replaced. So the clock splits the job:
 *
 *   phase 1  a single {@link Handler#postAtTime} message sleeps until the
 *            absolute uptime deadline. A plain Handler message cannot fire while
 *            the SoC is asleep, so it is structurally incapable of being a
 *            wakeup source.
 *   phase 2  at (or just after) the deadline it posts exactly ONE frame
 *            callback, so the actual work runs on the next hardware-synchronized
 *            frame and not at a random instant.
 *
 * The clock holds no Context, no Service and no wake lock. When it is cancelled
 * it leaves nothing queued: no Handler message and no frame callback.
 */
final class VsyncFrameClock {

    static final String TAG = "TL.Vsync";

    /** Process-wide singleton: one Choreographer, one queue, one pending job. */
    private static final VsyncFrameClock INSTANCE = new VsyncFrameClock();

    static VsyncFrameClock get() { return INSTANCE; }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Choreographer choreographer; // lazily bound on the main thread
    private Runnable pending;            // the logical job to run on the frame
    private long armedDeadlineUptimeMs = 0L;

    private VsyncFrameClock() { }

    private Choreographer choreo() {
        if (choreographer == null) {
            try { choreographer = Choreographer.getInstance(); }
            catch (Throwable t) { Log.w(TAG, "Choreographer unavailable", t); }
        }
        return choreographer;
    }

    // ------------------------------------------------------------------ control

    /**
     * Run {@code job} on the first hardware-synchronized frame at or after the
     * absolute uptime deadline {@code deadlineUptimeMs}. Any previously scheduled
     * job is cancelled first, so the clock never accumulates queued work.
     *
     * @param deadlineUptimeMs an absolute {@link SystemClock#uptimeMillis()} value
     * @param job              executed on the main thread, on a VSYNC frame
     */
    void scheduleAt(long deadlineUptimeMs, Runnable job) {
        cancel();
        if (job == null) return;
        pending = job;
        armedDeadlineUptimeMs = deadlineUptimeMs;

        long now = SystemClock.uptimeMillis();
        long delay = deadlineUptimeMs - now;
        if (delay < 0L) delay = 0L;
        // Phase 1: a plain, non-wakeup sleep until the second boundary.
        handler.postAtTime(phase1, now + delay);
    }

    /** Cancel everything this clock has queued. Idempotent and cheap. */
    void cancel() {
        pending = null;
        armedDeadlineUptimeMs = 0L;
        handler.removeCallbacks(phase1);
        Choreographer c = choreographer;
        if (c != null) {
            try { c.removeFrameCallback(phase2); } catch (Throwable ignored) { }
        }
    }

    boolean isArmed() { return pending != null; }
    long armedDeadlineUptimeMs() { return armedDeadlineUptimeMs; }

    // ------------------------------------------------------------------ engine

    /** Phase 1: the boundary was reached -- hand execution to the next frame. */
    private final Runnable phase1 = new Runnable() {
        @Override public void run() {
            if (pending == null) return;
            Choreographer c = choreo();
            if (c == null) { runPending(); return; }
            try {
                c.postFrameCallback(phase2);
            } catch (Throwable t) {
                Log.w(TAG, "postFrameCallback", t);
                runPending();
            }
        }
    };

    /** Phase 2: a hardware-synchronized frame arrived -- run the job. */
    private final Choreographer.FrameCallback phase2 = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            runPending();
        }
    };

    private void runPending() {
        Runnable job = pending;
        pending = null;
        armedDeadlineUptimeMs = 0L;
        if (job == null) return;
        try {
            job.run();
        } catch (Throwable t) {
            Log.w(TAG, "frame job", t);
        }
    }
}
