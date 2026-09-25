package com.vortex.timelock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * The engine. A single foreground service that:
 *   - tracks screen-on time with a dynamically registered ACTION_SCREEN_ON/OFF
 *     receiver (battery-free: no polling, no wake locks);
 *   - arms ONE passive (non-wakeup) AlarmManager trigger for the exact moment
 *     the limit is hit (build recipe 5: no Handler/Runnable loop at all);
 *   - owns the window-end + 15-min watchdog alarms and the platform JobScheduler
 *     routine that replaced every custom background loop/thread;
 *   - enters / leaves the brutal kiosk lock;
 *   - owns the live status-bar countdown, whose once-per-second redraw is drawn
 *     natively by SystemUI from the notification's chronometer fields, so this
 *     process is never woken to refresh it. See {@link LiveCountdown}.
 *
 * Battery: while the screen is off this service is completely idle (no timers,
 * no handlers, no wakeups). All timing is delegated to PASSIVE AlarmManager
 * triggers and to JobScheduler with low-power constraints, none of which can
 * hold a wake lock. That is why battery-saver / restricted-app-standby cannot
 * break it: there is nothing to freeze while idle, and a passive RTC alarm whose
 * time elapsed during sleep is delivered as soon as the device is interactive.
 */
public class TimeLockService extends Service implements LiveCountdown.Sink {

    static final String TAG = "TL.Service";

    static final String ACTION_RECHECK = "com.vortex.timelock.RECHECK";
    static final String ACTION_STOP = "com.vortex.timelock.STOP";

    /**
     * THE FOREGROUND-SERVICE CHANNEL.
     *
     * Deliberately a NEW channel id (not the legacy {@code tl_monitor}): a
     * NotificationChannel's importance is fixed at creation and can never be
     * raised afterwards, and {@code tl_monitor} was created at IMPORTANCE_MIN.
     * An FGS notification on a MIN channel is suppressed from the status bar --
     * the user cannot see that the guard is running and the OS is free to treat
     * the process as killable -- which is exactly the failure this hardening
     * removes. This channel is created once at IMPORTANCE_LOW (still silent: no
     * sound, no vibration, no badge) and is NEVER downgraded; see
     * {@link Engine#silenceNotifications}.
     */
    private static final String CH_SERVICE = "tl_service";
    private static final String CH_LOCK = "tl_lock";
    /**
     * The live-countdown channel. IMPORTANCE_LOW, no sound, no vibration, no
     * badge: the status-bar entry only ever changes one number per second and
     * that redraw is done natively by SystemUI off the notification's chronometer
     * fields (see {@link LiveCountdown}), never by this process.
     *
     * Package-private on purpose: {@link CountdownTicker} and LiveCountdown both
     * inspect this channel's importance to decide whether the countdown surface
     * is still permitted at all.
     */
    static final String CH_COUNTDOWN = "tl_countdown";
    private static final int NOTIF_MONITOR = 7001;
    private static final int NOTIF_LOCK = 7002;

    private static volatile boolean running = false;
    private static volatile TimeLockService sLive;

    static boolean isRunning() { return running; }

    /**
     * Hand a recheck straight to the live instance instead of issuing another
     * startForegroundService(). This is the fix for the FGS storm: rapid screen
     * on/off cycles and alarms no longer each spawn a fresh service start.
     * Returns true when the work was delivered to the running service.
     *
     * Recipe 5: the work is now delivered with a direct, synchronized call —
     * there is no Handler to post onto and therefore no persistent main-thread
     * queue for the engine.
     */
    static boolean requestRecheck(Context c, Intent intent) {
        TimeLockService s = sLive;
        if (s == null) return false;
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            s.stopSelf();
            return true;
        }
        String reason = intent == null ? null : intent.getStringExtra("reason");
        s.recheck(reason == null ? "inproc" : reason);
        return true;
    }

    private BroadcastReceiver screenRx;
    private boolean registered = false;
    private boolean lastScreenOn = true;

    // Last text pushed to the monitor notification, so identical updates do not
    // re-post to the NotificationManager on every screen event / alarm.
    private String lastMonitorText = null;

    // In-process guard: the full device-owner policy set only needs re-applying
    // when the lock is first entered (or on a fresh process after reboot, where
    // this resets to false and is therefore re-asserted). Keeps periodic
    // rechecks from hammering DevicePolicyManager every watchdog tick.
    private boolean lockApplied = false;

    /** The low-level countdown optimiser: owns the status-bar surface. */
    private LiveCountdown liveCountdown;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        sLive = this;
        // Finish any pending durable-store migration before anything evaluates the
        // lock. This also covers a START_STICKY service restart that happens
        // without going through Engine.bootstrap().
        DbMigration.run(this);
        // Repair any stale kiosk/launcher state the moment the engine comes up.
        // Safe BEFORE configuration: selfHeal can only ever DISABLE our own
        // kiosk component, so it cannot take over the launcher or the screen.
        Engine.selfHeal(this);
        // Bind the idle-power brain FIRST: it seeds its cached interactivity flag
        // with the single PowerManager read the engine is allowed per process,
        // after which every screen event is fed to it from our own receiver.
        PowerGovernor.attach(this);

        // COMPLETELY IDLE UNTIL CONFIGURED. Until the user has completed setup
        // (or we are the real Device Owner) we start NO channels, NO foreground
        // notification, NO screen-event listener and NO timers. onStartCommand()
        // stops the service immediately in that state, so nothing runs in the
        // background pre-configuration -- which removes the startup lag and the
        // interface glitches that happened before ownership was granted.
        if (!Prefs.activated(this) && !Engine.isDeviceOwner(this)) {
            Log.i(TAG, "created idle (not configured)");
            return;
        }

        createChannels();
        goForeground();
        registerScreenReceiver();
        // Recipe 5: the custom background loop is replaced by the platform
        // JobScheduler routine (low-power charging/idle constraints).
        EnforcerScheduler.ensure(this);
        // The live countdown surface: ONE status-bar entry, driven natively by
        // SystemUI, with every redundant render request stripped before it can
        // become a binder call (LiveCountdown). Registered with the governor, so
        // it parks itself the instant the panel goes off.
        liveCountdown = new LiveCountdown(this, this);
        liveCountdown.start();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        lastScreenOn = pm == null || pm.isInteractive();
        long bootNow = System.currentTimeMillis();
        // Recover any usage session a reboot/SIGKILL cut short, from the durable
        // heartbeat, before the first lock decision is taken. A no-op unless a
        // session was actually left open across a boot-epoch change.
        if (UsageStore.reconcileAfterRestart(this, bootNow)) {
            Prefs.setLastEvent(this, "usage:session-recovered acc=" + UsageStore.acc(this));
        }
        // The screen may already be on when the service starts, in which case we
        // never receive ACTION_SCREEN_ON. Without this the live session badge
        // (screenOnSince) stays 0, the remaining budget never shrinks and the
        // countdown timer would re-arm forever. Start tracking now.
        if (lastScreenOn) {
            UsageStore.startSession(this, bootNow);
        }
        Log.i(TAG, "created, interactive=" + lastScreenOn);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RECHECK : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Engine.selfHeal(this);
        // If the app is neither activated nor the device owner there is nothing
        // to enforce. Do not linger as a foreground service.
        if (!Prefs.activated(this) && !Engine.isDeviceOwner(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // HARDENING: (re-)anchor the foreground state on EVERY start command.
        // A START_STICKY restart can arrive without onCreate()'s startForeground
        // (the classic "the engine came back as a *background* service and could
        // then be killed" hole), and a dismissed or system-demoted ongoing
        // notification leaves the process foreground-less until we re-anchor here.
        goForeground();
        recheck("cmd");
        return START_STICKY; // auto-restart by the OS if ever killed
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // User swiped us away: come right back, we are a device-owner engine.
        Engine.bootstrap(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        sLive = null;
        lockApplied = false;
        if (liveCountdown != null) {
            Log.i(TAG, "destroy " + liveCountdown.report());
            liveCountdown.stop();
            liveCountdown = null;
        }
        PowerGovernor.detach();
        if (registered && screenRx != null) {
            try { unregisterReceiver(screenRx); } catch (Throwable ignored) {}
            registered = false;
        }
        // Only re-arm the self-heal watchdog + platform job while there is still
        // something to enforce; otherwise cancel every alarm/job so the engine
        // stays completely quiet (zero wake locks) until it is configured.
        if (Prefs.activated(this) || Engine.isDeviceOwner(this)) {
            EnforcerReceiver.armWatchdog(this);
            EnforcerScheduler.ensure(this);
        } else {
            EnforcerReceiver.cancelAll(this);
            EnforcerScheduler.cancel(this);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ==================================================================
    // Screen tracking
    // ==================================================================

    private void registerScreenReceiver() {
        if (registered) return;
        screenRx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) onScreenOn();
                else if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff();
                else if (Intent.ACTION_USER_PRESENT.equals(a)) recheck("user_present");
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            registerReceiver(screenRx, f);
            registered = true;
        } catch (Throwable t) {
            Log.e(TAG, "register screen rx", t);
        }
    }

    private void onScreenOn() {
        lastScreenOn = true;
        long now = System.currentTimeMillis();
        Engine.rollover(this, now);
        UsageStore.startSession(this, now);
        // Feed the transition to the governor BEFORE anything can post. It flips
        // its cached interactivity flag (no PowerManager call), closes the idle
        // session and dispatches onWake() -- the countdown then re-derives its
        // deadline from the wall clock. This broadcast is the ONLY wake signal
        // the optimisation model consumes from the OS.
        PowerGovernor.setInteractive(true);
        if (Engine.shouldBeLocked(this, now)) {
            enterLock(now);
        } else {
            scheduleLimitTick();
        }
        updateMonitorNotification();
    }

    private void onScreenOff() {
        lastScreenOn = false;
        long now = System.currentTimeMillis();
        long since = Prefs.screenOnSince(this);
        // Roll the window first, then bank the session delta ONLY when the window
        // did not roll. (Previously rollover() ran after the delta was added, so
        // crossing the reset boundary could silently discard the session.)
        boolean rolled = Engine.rollover(this, now);
        if (!rolled && since > 0 && now > since) {
            // Bank the session into the durable counter: one fsync'd transaction,
            // committed before anything else runs, so a kill right here loses
            // nothing.
            UsageStore.bankSession(this, now);
        } else {
            // Window rolled (counter already reset): just close the session.
            UsageStore.endSession(this, now);
        }
        cancelTick();
        // Panel off: the governor closes the idle window and parks every
        // Suspendable -- the countdown's fallback ticker included -- for the
        // whole dark period. Nothing is armed, nothing is posted, no timer runs.
        PowerGovernor.setInteractive(false);
        if (Engine.shouldBeLocked(this, now)) enterLock(now);
        updateMonitorNotification();
    }

    // ==================================================================
    // Core re-evaluation
    // ==================================================================

    private void recheck(String reason) {
        long now = System.currentTimeMillis();

        // HARD IDLE GATE: while the app is neither activated nor the owner there
        // is nothing to enforce and nothing that could enforce it. Cancel every
        // alarm, clear the countdown and return. This breaks the old
        // self-perpetuating loop (recheck -> arm watchdog -> watchdog -> start
        // service -> recheck ...) that kept the device busy before setup.
        if (!Prefs.activated(this) && !Engine.isDeviceOwner(this)) {
            EnforcerReceiver.cancelAll(this);
            cancelTick();
            PowerGovernor.forgetAll();
            PowerGovernor.noteRecheck(reason);
            Prefs.setLastEvent(this, "recheck:" + reason + " idle");
            return;
        }

        boolean rolled = Engine.rollover(this, now);
        // Refresh the durable deadman heartbeat on every re-evaluation so an
        // abrupt kill is always recoverable up to at most this interval.
        UsageStore.heartbeat(this, now);
        PowerGovernor.noteRecheck(reason);
        Prefs.setLastEvent(this, "recheck:" + reason + (rolled ? " roll" : ""));

        boolean locked = Engine.shouldBeLocked(this, now);
        if (locked) {
            enterLock(now);
        } else {
            exitLock();
            // No cancelTick() here: scheduleLimitTick() cancels on every path
            // that must not hold a trigger, and cancelling inline would defeat
            // the re-arm coalescing (see the note inside scheduleLimitTick).
            scheduleLimitTick();
            // long-range safety nets, always present
            PowerGovernor.armPassiveExact(this, Engine.windowEndFor(now, Prefs.anchorMin(this)),
                    EnforcerReceiver.ACTION_WINDOW_END, EnforcerReceiver.REQ_END);
            EnforcerReceiver.armWatchdog(this);
        }
        updateMonitorNotification();
    }

    /** Bring the kiosk up and arm the precise unlock alarm. */
    private void enterLock(long now) {
        // HARD GUARD: never take over the screen unless we are the real Device
        // Owner. Without ownership we only post one calm notification. This is
        // the fix for the old "screen blinking / random apps" chaos.
        if (!Engine.canEnforce(this)) {
            showLimitReachedNotice();
            return;
        }
        // Never lock without an actual, user-set limit being exhausted.
        if (!Engine.shouldBeLocked(this, now)) return;
        long until = Engine.lockUntilMs(this, now);
        Prefs.setLockUntil(this, until);
        Prefs.setLocked(this, true);
        if (!lockApplied) {
            Engine.applyLockState(this, true);
            lockApplied = true;
        } else {
            // Already locked: make sure the kiosk is on screen right now. This is
            // what brings the lock screen straight back after the panel was turned
            // off (graceful screen-off warning) or after any interruption.
            Engine.refreshKiosk(this);
        }
        // Coalesced: an unlock instant that has not moved is now free to re-arm.
        PowerGovernor.armPassiveExact(this, until,
                EnforcerReceiver.ACTION_WINDOW_END, EnforcerReceiver.REQ_END);
        cancelTick();
        showLockNotification(until);
        updateMonitorNotification();
        Log.i(TAG, "LOCKED until " + until);
    }

    /** Leave the kiosk (only ever happens at window rollover). */
    private void exitLock() {
        if (!Prefs.locked(this)) return;
        Prefs.setLocked(this, false);
        Prefs.setLockUntil(this, 0L);
        Engine.applyLockState(this, false);
        lockApplied = false;
        hideLockNotification();
        updateMonitorNotification();
        Log.i(TAG, "UNLOCKED");
    }

    /**
     * Arms ONE passive (non-wakeup) AlarmManager trigger for the exact moment the
     * remaining screen-on budget is exhausted (BUILD RECIPE 5).
     *
     * This is the replacement for the old in-process Handler/Runnable countdown:
     * there is no Handler, no posted Runnable and no convergence state kept in
     * sync. The trigger is an exact RTC (NOT RTC_WAKEUP) alarm owned by
     * {@link EnforcerReceiver}, so it can never hold the SoC awake and costs no
     * wake lock; when the screen turns off the trigger is cancelled and the engine
     * is completely idle until the next screen-on. An exact RTC alarm whose time
     * elapsed during sleep is delivered the moment the device is next
     * interactive -- exactly when enforcement must converge -- and allow-while-idle
     * keeps Doze / app-standby from deferring it further.
     *
     * When it fires it lands in EnforcerReceiver (ACTION_TICK) ->
     * Engine.reevaluate, which re-runs recheck(): if the budget is spent we enter
     * the lock, otherwise the active trigger is re-armed. That re-arm converges on
     * its own because the remaining budget only ever shrinks while the screen is
     * on, so it never spins.
     */
    private void scheduleLimitTick() {
        // No user-set limit => nothing to count down to.
        if (Engine.limitMs(this) <= 0L) { cancelTick(); return; }
        // Nothing to enforce while unconfigured: never arm a trigger at all.
        if (!Prefs.activated(this) && !Engine.isDeviceOwner(this)) { cancelTick(); return; }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        long now = System.currentTimeMillis();

        // Keep the live session badge honest: if the screen is on but no session
        // is being tracked (service start while the screen was already on never
        // got ACTION_SCREEN_ON), start tracking now so the budget advances.
        if (interactive) {
            UsageStore.startSession(this, now);
            // Durable heartbeat, committed immediately: bounds what an abrupt
            // SIGKILL-then-reboot could leave unrecovered to one recheck interval.
            UsageStore.heartbeat(this, now);
        }

        long rem = Engine.remainingMs(this, now);
        if (rem <= 0) { enterLock(now); return; }

        // Only meaningful while the screen is on; guard anyway.
        if (!interactive) { cancelTick(); return; }

        // ONE exact, passive trigger at the moment the budget hits zero, routed
        // through the governor's coalescing layer.
        //
        // NOTE: there is deliberately NO cancelTick() on this path. Cancelling
        // first would clear the governor's coalescing target and force a real
        // setExactAndAllowWhileIdle() binder call on EVERY screen event -- exactly
        // the redundant wake-cycle traffic this model exists to strip. The target
        // computed here is identical to the one already armed while the screen is
        // on (deadline = limit - acc + screenOnSince is independent of `now`), so
        // the arm collapses to nothing and only really happens when the deadline
        // genuinely moved (session banked, re-anchored, alarm delivered).
        PowerGovernor.armPassiveExact(this, now + rem,
                EnforcerReceiver.ACTION_TICK, EnforcerReceiver.REQ_TICK);
    }

    private void cancelTick() {
        EnforcerReceiver.cancelLimit(this);
        // Drop the coalescing target as well: the next arm must not be mistaken
        // for a redundant re-arm of a trigger that no longer exists.
        PowerGovernor.forget(EnforcerReceiver.ACTION_TICK);
    }

    // ==================================================================
    // Notifications
    // ==================================================================

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // The foreground-service channel. IMPORTANCE_LOW, NOT MIN: the ongoing
        // notification that keeps this process foreground must be VISIBLE in the
        // status bar, and an IMPORTANCE_MIN channel is suppressed there. LOW is
        // still completely silent -- the rest is stripped explicitly so the
        // persistent entry can never ring, buzz or badge.
        NotificationChannel svc = new NotificationChannel(CH_SERVICE,
                getString(R.string.srv_title), NotificationManager.IMPORTANCE_LOW);
        svc.setDescription(getString(R.string.srv_text));
        svc.setShowBadge(false);
        svc.enableVibration(false);
        svc.setSound(null, null);
        svc.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(svc);

        // The live countdown channel. IMPORTANCE_LOW, deliberately NOT MIN: MIN
        // notifications are suppressed from the status bar outright, and a status
        // bar entry is the entire point here. LOW is silent, and the rest is
        // stripped explicitly -- no sound, no vibration, no badge -- so a
        // once-per-second native redraw can never ring, buzz or light anything up.
        NotificationChannel cd = new NotificationChannel(CH_COUNTDOWN,
                getString(R.string.srv_countdown), NotificationManager.IMPORTANCE_LOW);
        cd.setShowBadge(false);
        cd.enableVibration(false);
        cd.setSound(null, null);
        cd.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(cd);

        NotificationChannel lock = new NotificationChannel(CH_LOCK, "Screen time lock",
                NotificationManager.IMPORTANCE_HIGH);
        lock.setShowBadge(false);
        lock.setBypassDnd(true);
        lock.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(lock);
    }

    private PendingIntent lockContentIntent() {
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 7100, i, flags);
    }

    /**
     * Content intent for every status notification (persistent monitor,
     * live countdown and the lock surface): tapping one opens the READ-ONLY
     * status screen by explicit component.
     *
     * <p>This deliberately targets {@link StatusActivity} and NOT
     * {@link LauncherDashboard}: the latter is a MAIN+LAUNCHER entry, so
     * {@link Engine#freezeLaunchers} disables it the moment a full setup is
     * activated -- which is exactly why tapping the persistent notification on a
     * locked-in install used to do nothing. StatusActivity carries no launcher
     * and no HOME category, so it is never frozen and always opens.
     */
    private PendingIntent statusContentIntent() {
        Intent i = new Intent();
        i.setClassName(this, "com.vortex.timelock.StatusActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 7101, i, flags);
    }

    private Notification buildMonitorNotification() {
        return monitorNotification(System.currentTimeMillis());
    }

    // ==================================================================
    // Foreground / keep-alive hardening
    // ==================================================================

    /**
     * Enter (or re-enter) the foreground state with the persistent notification.
     *
     * Wrapped so it can never take the process down: on API 34+ the typed
     * three-argument form is used (an app targeting 34 with a specialUse service
     * MUST declare and pass its type), with a plain fallback if the platform
     * rejects the stronger call.
     */
    private void goForeground() {
        Notification n = buildMonitorNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_MONITOR, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_MONITOR, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground typed failed, falling back", t);
            try { startForeground(NOTIF_MONITOR, n); } catch (Throwable ignored) {} 
        }
    }

    /** Re-post the persistent surface and re-anchor the foreground state. */
    private void reassertForeground() {
        if (!Prefs.activated(this) && !Engine.isDeviceOwner(this)) return;
        goForeground();
        // The surface was just (re)posted by goForeground()/startForeground(); drop
        // the render memory so the live countdown re-pushes immediately instead of
        // being swallowed by the same-second dedupe after a dismissal.
        if (liveCountdown != null) liveCountdown.invalidate();
        updateMonitorNotification();
    }

    private PendingIntent keepAliveIntent() {
        Intent i = new Intent(this, NotificationGuardReceiver.class);
        i.setAction(NotificationGuardReceiver.ACTION_NOTIF_DISMISSED);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, 7300, i, flags);
    }

    /**
     * Best-effort keep-alive: re-anchor the running instance directly, otherwise
     * bootstrap the engine (which restarts the service via startForegroundService).
     * Called from the notification's deleteIntent (shade cleared / swipe-away) and
     * from the passive 15-minute watchdog as belt-and-suspenders. Performs no
     * scheduling of its own, so it adds no timer and no wakeup.
     */
    static void requestKeepAlive(Context c) {
        if (c == null) return;
        TimeLockService s = sLive;
        if (s != null) { s.reassertForeground(); return; }
        Engine.bootstrap(c);
    }

    /**
     * THE STATIC SURFACE: one line of text. Used for the startForeground()
     * bootstrap and whenever the live countdown is not wanted (locked, opt-out)
     * or not permitted. Posting is deduped against the last text actually sent, so
     * a re-evaluation that cannot change the pixels costs zero binder calls.
     */
    private Notification monitorNotification(long now) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_SERVICE);
        else b = new Notification.Builder(this);
        boolean own = Engine.isDeviceOwner(this);
        long rem = own ? Engine.remainingMs(this, now) : 0L;
        boolean hasRem = own && rem > 0L;
        b.setSmallIcon(R.drawable.ic_launcher)
                // Remaining time lives in the TITLE; the description is the label.
                .setContentTitle(hasRem ? fmt(rem) : monitorText(now))
                .setContentText(hasRem ? getString(R.string.srv_countdown_text)
                                       : getString(R.string.srv_title))
                .setOngoing(true)                 // not swipe-dismissable in normal use
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(statusContentIntent());
        // Non-removable under Android 13+/14: the platform lets the user swipe a
        // foreground-service notification away for some service types. This
        // delete intent re-asserts the foreground state the instant that happens,
        // so clearing the shade cannot silence the guard.
        b.setDeleteIntent(keepAliveIntent());
        if (Build.VERSION.SDK_INT >= 31) {
            // Show the FGS notification IMMEDIATELY (default is a 10s delay): the
            // process is foreground, and therefore unkillable, from the first
            // frame, and the surface appears in step with it.
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private String monitorText(long now) {
        return Engine.isDeviceOwner(this)
                ? remainingText(now)
                : getString(R.string.srv_text);
    }

    /**
     * The remaining-time line of the static surface.
     *
     * It is derived from the SAME persisted accounting state the enforcement
     * trigger is armed against, and it is structurally incapable of rendering the
     * "00:00:00" placeholder the engine used to fall back to:
     *
     *   - no limit configured  -> an explicit "not set" line, never a clock;
     *   - budget exhausted     -> an explicit "reached" line, never a clock;
     *   - otherwise            -> the real remaining budget, recomputed from the
     *                             persisted window anchor + screen-on accumulator
     *                             on every call, so a cold start, a killed process
     *                             or a device that rebooted shows the true
     *                             remaining time rather than a zero placeholder.
     */
    private String remainingText(long now) {
        long lim = Engine.limitMsFor(this, now);
        if (lim <= 0L) return getString(R.string.srv_no_limit);
        long rem = Engine.remainingMs(this, now);
        if (rem <= 0L) return getString(R.string.srv_limit_reached);
        return getString(R.string.srv_remaining_prefix) + " " + fmt(rem);
    }

    /** Static surface + last-text dedupe. @return true when a post really went out. */
    private boolean postStaticMonitor(long now) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        String txt = monitorText(now);
        if (txt.equals(lastMonitorText)) return false;
        lastMonitorText = txt;
        try {
            nm.notify(NOTIF_MONITOR, monitorNotification(now));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Every "the status bar may need to change" event in this engine lands here:
     * screen on/off, the limit alarm, rechecks, lock/unlock, config changes.
     *
     * It is a single funnel on purpose. {@link LiveCountdown} decides whether
     * anything actually has to reach the NotificationManager, so binder posts are
     * O(real state changes) instead of O(re-evaluations x state changes).
     */
    private void updateMonitorNotification() {
        if (liveCountdown != null) {
            liveCountdown.update(System.currentTimeMillis());
            return;
        }
        // Pre-wiring bootstrap (service up, optimiser not attached yet).
        postStaticMonitor(System.currentTimeMillis());
    }

    private void showLockNotification(long untilMs) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_LOCK);
        else b = new Notification.Builder(this);
        String when = LockActivity.formatClock(untilMs);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.lock_title))
                .setContentText(getString(R.string.lock_subtitle) + "  (" + when + ")")
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(statusContentIntent());
        if (Build.VERSION.SDK_INT >= 29 && Engine.canEnforce(this)) {
            b.setFullScreenIntent(lockContentIntent(), true);
        }
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_MAX);
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        try { nm.notify(NOTIF_LOCK, b.build()); } catch (Throwable ignored) {}
    }

    private void hideLockNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_LOCK);
    }

    /**
     * Calm, NON-intrusive notice used when the limit is reached but we are not
     * the device owner: a normal notification that opens the setup screen. No
     * full-screen intent, no activity launch, no screen takeover of any kind.
     */
    private void showLimitReachedNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent i = new Intent(this, SetupActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 7200, i, flags);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_LOCK);
        else b = new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.notif_not_owner_t))
                .setContentText(getString(R.string.notif_not_owner_x))
                .setAutoCancel(true)
                .setShowWhen(false)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_DEFAULT);
        try { nm.notify(NOTIF_LOCK, b.build()); } catch (Throwable ignored) {}
    }

    // ==================================================================
    // LiveCountdown.Sink -- the status-bar surface this service owns
    // ==================================================================

    /**
     * TRUE only when a live countdown is meaningful right now.
     *
     * Deliberately cheap and binder-free: it runs on every render request, so it
     * only reads already-cached SharedPreferences values. Locked => no countdown:
     * the budget is frozen and the lock notification owns the screen.
     */
    @Override
    public boolean countdownWanted() {
        return Prefs.optCountdown(this)
                && Prefs.activated(this)
                && !Prefs.locked(this)
                && Engine.limitMs(this) > 0L;
    }

    /**
     * The wall-clock instant the remaining budget hits zero, or 0 when there is
     * none. This is the SAME deadline the enforcement trigger is armed against, so
     * the number in the status bar and the moment the lock fires cannot disagree.
     */
    @Override
    public long deadlineMs(long now) {
        long rem = Engine.remainingMs(this, now);
        return rem > 0L ? now + rem : 0L;
    }

    @Override
    public boolean render(long now, boolean nativeChronometer, long deadlineMs) {
        return nativeChronometer ? postCountdown(now, deadlineMs) : postStaticMonitor(now);
    }

    /**
     * THE LIVE SURFACE: the remaining time drawn by SystemUI's native chronometer.
     *
     * ONE binder post arms the countdown ({@code setUsesChronometer(true)} +
     * {@code setChronometerCountDown(true)} with the deadline in {@code setWhen});
     * after that the OS repaints the seconds entirely on its own. The app performs
     * NO per-second re-post, so a ticking countdown costs zero app wakeups and no
     * binder traffic while it runs. The static label lives in the title/description
     * and the render dedupe in LiveCountdown keys on the (constant, screen-on)
     * deadline so repeated re-evaluations are dropped before they reach the
     * NotificationManager. While the screen is off nothing is posted at all.
     */
    private boolean postCountdown(long now, long deadlineMs) {
        if (deadlineMs <= now) return postStaticMonitor(now);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CH_COUNTDOWN);
        else b = new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
                // Static label only. The ticking seconds are drawn by SystemUI's
                // native chronometer, so this notification is posted ONCE per
                // deadline change and never re-posted per second.
                .setContentTitle(getString(R.string.srv_title))
                .setContentText(getString(R.string.srv_countdown_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)   // a refresh must never re-alert
                .setShowWhen(true)        // the chronometer needs the `when` field
                .setWhen(deadlineMs)
                .setContentIntent(statusContentIntent())
                .setDeleteIntent(keepAliveIntent());
        if (Build.VERSION.SDK_INT >= 24) {
            b.setUsesChronometer(true);
            b.setChronometerCountDown(true);
        }
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        try {
            nm.notify(NOTIF_MONITOR, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "post countdown", t);
            return false;
        }
        // Invalidate the static dedupe: if we ever fall back to the static text,
        // the first post must not be swallowed as a duplicate of stale content.
        lastMonitorText = null;
        return true;
    }

    /**
     * Memory pressure: hand it to the governor, which prunes stale alarm targets
     * and fans the callback out to every Suspendable -- this service's countdown
     * optimiser included -- so its cached permission probe and surface state are
     * dropped and rebuilt small.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        PowerGovernor.onTrimMemory(level);
    }

    // ==================================================================

    static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000L;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sec);
    }
}
