# Changelog

Notable changes to Screen Time Locker, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0) and [Semantic Versioning](https://semver.org/).

## 6.3.6 - 2026-09-26

### Fixed
- **Screen-time undercount at the daily reset boundary (screen-off data loss).** When the screen went off *after* the window had rolled over, `TimeLockService.onScreenOff()` rolled the window and then called `UsageStore.endSession()`, which silently **discarded** the entire live session - including every post-boundary minute of screen-on time. `Engine.rollover()` already re-anchors a session that straddled the boundary to the new window start, so the correct behaviour is to bank the session unconditionally. `onScreenOff()` now always calls `UsageStore.bankSession()` after `rollover()`, recording the whole session when no boundary was crossed and only the post-boundary slice when one was.
- **Background screen-on tracking while un-set-up.** The foreground service, the `ACTION_SCREEN_ON/OFF` receiver and the screen-on session tracker ran whenever the app was *either* activated *or* the device owner (`!Prefs.activated() && !Engine.isDeviceOwner()` gate). A Device-Owner install that had never completed setup therefore kept counting screen-on time in the background with no schedule to enforce. Every tracking/enforcement gate in `TimeLockService` (onCreate, onStartCommand, recheck, scheduleLimitTick, onDestroy, reassertForeground) is now keyed on `Prefs.activated()` alone: no activation => no schedule => nothing to enforce, nothing to count, and the service stays idle.

### Notes
- Version bumped from 6.3.5 (versionCode 19) to 6.3.6 (versionCode 20).

## 6.3.5 - 2026-09-25

### Fixed
- **Kiosk re-arms IMMEDIATELY at boot, before the passcode, with zero permissions.** `LockActivity` is now `android:directBootAware="true"`. Without that flag the direct-boot receiver could re-assert policy pre-unlock but the follow-up `startActivity` was silently dropped by the package manager, so an ongoing lock only reappeared after the user unlocked. With the flag the kiosk is rendered from the device-protected mirror the instant the framework is up - no runtime permission, no OEM autostart whitelist, no CE storage, no timer and no wakelock.
- **The reset-time picker now speaks the device's clock.** The console's *Reset time* card previously forced a raw 0-23 hour field regardless of the phone's time format, so a user on a 12-hour device had no way to choose AM or PM. On a 12-hour device the hour field is now 1-12 and a segmented **AM / PM** toggle appears beside it; on a 24-hour device the 0-23 field is kept, unchanged. Switching the phone's 12/24-hour setting re-renders the picker (and preserves the configured anchor) without a re-setup.

### Notes
- **Global 12/24-hour consistency.** Every wall-clock string in the app already routes through `TimeFmt` (lock (kiosk) clock, unlock/deadline labels, resets-at preview, live "updated" stamps), so an already-enabled lock instantly follows the device's 12/24-hour preference after this update - no re-arm or re-configuration needed. Durations (`HH:MM:SS` countdowns, `USED` read-outs) deliberately keep their meridiem-free shape.
- Version bumped from 6.3.4 (versionCode 18) to 6.3.5 (versionCode 19).

## 6.3.4 - 2026-09-25

### Added
- **Special-access appop pinning** (`ShizukuHardener`). The shell pass now also pins the app-ops a user CAN otherwise flip back from *Settings -> Special app access*, which `DevicePolicyManager` exposes no setter for:
  - `cmd appops set <pkg> USE_FULL_SCREEN_INTENT allow` - keeps the lock screen's full-screen intent from being downgraded.
  - `cmd appops set <pkg> SCHEDULE_EXACT_ALARM allow` - keeps the exact-alarm timer behind the countdown.
  - `cmd appops set <pkg> POST_NOTIFICATION allow` - a third pin on the notification channel, alongside the DO grant state and the `pm grant`.
- **Foreground runtime-pin re-assert** (`Engine.reassertRuntimePins`), called from `StatusActivity.onResume` and `GatekeeperActivity.onResume`. It re-pins the declared runtime permission set to its non-revocable `GRANTED` state on every resume, closing the window between a permission being granted and the next boot / power-state / job pass. It is DPM-policy only - no storage write, no thread, no wakelock - so `StatusActivity`'s read-only-preference contract is preserved.

### Notes
- **Battery cost unchanged (zero).** No new timer, alarm, thread or wakelock was added. The resume path is a single owner check plus a few idempotent binder calls, and it returns immediately when the app is not the Device Owner.
- The "once granted, never ungrantable" guarantee remains **Device-Owner-only** by platform design: a plain Device Admin cannot pin permissions. `reassertRuntimePins` is a no-op unless `isDeviceOwner()`.
- Version bumped from 6.3.3 (versionCode 17) to 6.3.4 (versionCode 18).

## 6.3.3 - 2026-09-25

### Added
- **Shell-level permission permanence** (`ShizukuHardener`). The two states the DevicePolicyManager layer cannot install - the Doze **battery-optimization exemption** and the background / OEM **autostart** allow-list - are now written over the app's privileged Shizuku shell at the SYSTEM level, so they persist across reboots, updates and Settings visits:
  - `cmd deviceidle whitelist +<pkg>` - persists the exemption in the framework's `deviceidle.xml`.
  - `am set-standby-bucket <pkg> active` - never bucketed into App Standby.
  - `cmd appops set <pkg> RUN_IN_BACKGROUND allow` and `RUN_ANY_IN_BACKGROUND allow` - the AOSP background-run/autostart allow-list.
  - `pm grant <pkg> <perm>` for every declared runtime permission, `POST_NOTIFICATIONS` included, as a belt-and-braces pin alongside the device-owner grant state.
- Wired into `Engine.enforcePermissionPermanence` and `Engine.bootstrap`, so it re-asserts on boot, on the idle/charging JobScheduler reconciliation, on a Doze/Battery-Saver posture change, on an owner event and on a lock-state change.

### Notes
- **Battery-free by construction.** The hardener adds no timer, no alarm, no polling loop and no wakelock. It runs only from the existing (already rare) event hooks, executes on a single short-lived daemon thread so no receiver main thread is ever blocked, collapses event bursts with a 90 s cooldown, and returns instantly when Shizuku is not running / authorised.
- When Shizuku is unavailable the earlier graceful path still applies: the device-owner pin stands and `Engine.reassertBatteryIfLost` re-requests the exemption from the foreground the instant it is lost.
- OEM vendor autostart lists that are not backed by AOSP app-ops remain unsettable by any public API; the app-ops above are the portable half of that switch.
- Version bumped from 6.3.2 (versionCode 16) to 6.3.3 (versionCode 17).

## 6.3.2 - 2026-09-25

### Added
- **Permission permanence layer** (`Engine.enforcePermissionPermanence`). Once the app is Device Owner, every runtime permission it declares — `POST_NOTIFICATIONS` included — is pinned with `setPermissionGrantState(..., GRANTED)`, which makes it **non-user-manageable**: Settings shows the toggle greyed out and the user cannot revoke it. `setUninstallBlocked` and the App-Standby flag (`app_standby_enabled=0`) are re-asserted in the same pass.
- The pin is now **sticky**: it no longer follows `Prefs.activated`, so deactivating the schedule does not release the device-owner permission pin.
- `PowerStateReceiver` — a new exported=false receiver on the protected system broadcasts `DEVICE_IDLE_MODE_CHANGED` and `POWER_SAVE_MODE_CHANGED`, so the permanence layer is re-asserted the instant the Doze / Battery-Saver posture changes.
- `Engine.reassertBatteryIfLost(Activity)` — restores a dropped battery-optimization exemption from the foreground (the only context Android 29+ permits). Gated on a completed setup and rate-limited to one request per 45 s so it can never fight the onboarding STEP_BATTERY row or stack dialogs.
- The permanence layer is now also driven from `EnforcerJobService` (every periodic work run), `BootReceiver` post-unlock (unconditional, owner-gated) and `GatekeeperActivity.onResume`.

### Notes
- Honest limits: Android exposes **no** Device-Owner API that pins the Doze battery-optimization *exemption*, and vendor "autostart" has no API at all. Both are handled by re-check + re-request instead of a hard pin; a Device Owner is otherwise exempt from App Standby and the autostart whitelist.
- Version bumped from 6.3.1 (versionCode 15) to 6.3.2 (versionCode 16).

## 6.3.1 - 2026-09-25

- `StatusActivity`: a day with no lock configured no longer reads like missing data. The **Daily limit** tile now shows **Unlimited** instead of "Not set", and **Time left** shows the open-ended `♾️ Until <next locked weekday> <reset time>` instead of a bare dash. When no weekday carries a lock at all, it reads `♾️ Unlimited`.
- The matching rows in the **Current status** card (Today's limit, Time left) follow the same wording.

## 6.3.0 - 2026-09-25

- Package renamed from `com.sentinel.timelock` to `com.vortex.timelock` throughout the source, manifest, Shizuku provider authority and documentation. This is a new application identity, so it installs alongside an existing `com.sentinel.timelock` build rather than upgrading it.
- Added `StatusActivity`, an always-available read-only status screen. Every notification (persistent monitor, live countdown and the lock surface) now opens it by explicit component instead of `LauncherDashboard`, which the engine freezes once a full setup is activated.
- Source continues to ship only the public test signing key; release builds are signed with the maintainer's private keystore.

## 6.2.0 - 2026-09-25

- Open sourced: full source, documentation, and a standalone build script that needs no Gradle or Android Studio.
- Ships a public test signing key so a fresh clone builds an installable APK with no setup. Marked `DO NOT SHIP`.
- Added beginner docs for architecture, how it works, installation, FAQ, troubleshooting and philosophy.
- The build script is now portable. It finds the SDK, build-tools, `android.jar` and `kotlin-stdlib`, and can sign with your own keystore through environment variables.
- Removal policy stated plainly. There is no `adb` path to disable the lock and no in-app release. The only revert is a factory reset, and only if *Block factory reset* was left off. If it was on, the device must be re-flashed.
- Enforcement verified by static inspection of the compiled dex and packaged manifest, not by a full live-device test.

## 6.1.0 - 2026-09-25

- Works with zero runtime permissions as long as the app is Device Owner.
- `BootReceiver` is direct-boot aware and handles `LOCKED_BOOT_COMPLETED` and `USER_UNLOCKED`, so the kiosk re-arms before the user unlocks.
- Added `BootState`, a device-protected mirror of the small pre-unlock state slice.
- `applyPermissionLockdown` is now package-scoped. The device-wide `setPermissionPolicy` call was removed.

## 6.0.0 - 2026-09-25

- Biometric-only admin console. Every stored secret removed, no PIN, salt, digest or escrow key.
- Hardened kiosk: lock-task kiosk, disabled status bar and keyguard, persistent HOME override.
- Removed the emergency release key. There is now no way out except the end of the window.

## 5.0.0 - 2026-09-24

- Reboot-safe engine: exact alarm at window end plus a 15-minute watchdog.
- Per-weekday schedules, a live per-second status-bar countdown, and a light and dark design-token layer.

## 1.0.0 - 2026-09-24

- First engine: screen-on-time ledger, foreground service, screen on and off receivers, and the Device-Owner lock at budget exhaustion.
