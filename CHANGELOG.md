# Changelog

Notable changes to Digital Retreat, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0) and [Semantic Versioning](https://semver.org/).

## 8.0 - 2026-09-26

### Changed
- **Digital Retreat restyle — presentation only, no behaviour changes.** The app is now branded and themed as a *Digital Retreat* tool. Every string, colour, style, drawable and launcher icon moved to a calm, nature-toned retreat palette (deep forest surfaces, moss/leaf accent, warm-sun highlights) and the copy throughout the onboarding gate, lock screen and dashboard speaks in retreat language (*Resting — see you soon*, *Prepare your retreat*, *The retreat is in progress*). Layouts, resource names, class names, package id (`com.vortex.timelock`), the Device-Owner provisioning flow and every enforcement path are untouched.
- **Version identity bumped** to `versionCode` 35 / `versionName` 8.0 to mark the re-skin.

### Notes
- This release changes only appearance and wording. No logic, permission, manifest component or build step was altered; the app builds to the same package and behaves identically.

## Unreleased

### Added
- **Kiosk Shortcuts card.** The lock screen's *Recent activity* log has been removed and replaced by a **SHORTCUTS** card that documents the hardware gestures available to the person holding the locked phone. The card is built once and never rebound, so it adds nothing to the per-second render cost, and a future shortcut is a single `addShortcut` call. The first shortcut is the volume rocker.
- **Volume-rocker brightness shortcut.** Inside the kiosk the volume rocker no longer changes media volume: Volume Up brightens and Volume Down dims the panel. One press moves the level by a fixed `Brightness.STEP` (10/255, about 4%), clamped to `Brightness.MIN_LEVEL`..`MAX` so a press can dim the screen but never black it out. The current level is re-read from `Settings.System` on every press and written through the Device-Owner `Brightness.setLevel` path (adaptive mode is cleared first), and the on-screen slider and the percentage are nudged on the same frame so the read-out never lags the key.

### Changed
- **The kiosk no longer intercepts the power button, and no longer forces the panel on.** `KioskContainer.hardenWindow` drops `FLAG_TURN_SCREEN_ON` / `setTurnScreenOn(true)`, which previously made the panel come back on a moment after the user switched it off with the power button (and then fall asleep again about 10 s later when the display-sleep watchdog fired). `KEYCODE_POWER` is deliberately absent from the container's blocked-key set, so pressing the power button now switches the screen off and it stays off; lock-task plus the persistent HOME preference still keep the kiosk on top, so it is already there when the screen is switched back on. The volume keys are no longer blocked either - they are re-purposed as the brightness shortcut.
- **The lock screen no longer shows the activity log.** `KioskDashboard` drops the `RECENT ACTIVITY` card, its pre-built row pool and the per-tick activity bind. The underlying `KioskLog*` helpers are unchanged and event recording continues; only the lock-screen projection changed.
- **Full documentation pass.** Every Markdown file (`README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`, `libs/README.md` and all of `docs/`) rewritten for accuracy, consistency and clarity.
- Architecture and libs docs now reflect the classes actually shipped: `BatteryGuard`, `ShizukuHardener`, `Brightness`, `StatusActivity`, `PowerStateReceiver`, `TimeFmt`, the `KioskLog*` helpers, `SplashActivity` and `TransitionGate` are documented, and `libs/` no longer claims Shizuku is used for a single purpose.
- Documentation cross-links, tone and formatting standardised across the set; troubleshooting lines now quote the real commands and output.

### Notes
- The kiosk changes above touch only `KioskContainer`, `KioskDashboard` and `LockActivity`; no manifest, build script or version identity changed, and the app still builds to the same package.
- The following documentation entries are from the earlier documentation-only pass.

## 7.2 - 2026-09-26

### Changed
- **Lock-mode power policy is now fully native (`BatteryGuard`) — no ADB, no Shizuku.** The moment the kiosk lock engages, the app quiets the rest of the phone using only the Device-Owner `DevicePolicyManager` API instead of the privileged Shizuku shell. It calls `setPackagesSuspended` on every third-party package, which force-stops each one **and keeps it from restarting** (no broadcast, alarm, sync or service restart) until unlock — strictly stronger than the old one-shot `am kill-all`. The foreground kiosk (this app) and every system package are left untouched. Wi-Fi is switched off through the Device-Owner-exempt `WifiManager.setWifiEnabled`, and the exact pre-lock state (Wi-Fi on/off and the precise set of suspended packages) is stored, so unlock restores precisely what the owner had and never turns on a radio that was already off.
- **`setPackagesSuspended` is gated at API 24, not API 30.** The bulk suspend call has existed since Android 7.0; gating it at API 30 would have silently disabled app-killing on Android 8–10 even though the API is present there. It now works on every supported device (`minSdkVersion` 26).

### Notes
- **Mobile data cannot be toggled by a Device Owner on stock Android.** `mobile_data` is not in the `setGlobalSetting` allow-list and `TelephonyManager.setDataEnabled` needs `MODIFY_PHONE_STATE` (system-only). The policy therefore leaves the cellular radio exactly as the owner set it; suspending every user app means no third-party app can use mobile data either, which is the same practical outcome for everything the kiosk can reach.
- The manifest gains `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` for the native Wi-Fi toggle; Shizuku is still used only for the one-time Device-Owner provisioning and the optional shell-level hardening, never for the lock-mode power policy.

## 7.1 - 2026-09-26

### Added
- **Lock-mode power policy (`BatteryGuard`).** The moment the kiosk lock engages, the app makes the rest of the phone quiet so the locked screen costs as little battery as possible. Over the same privileged Shizuku shell channel the onboarding chain already uses, it runs three idempotent actions: `am kill-all` stops every background user app (so nothing left in the background keeps a wakelock, fires an alarm, runs a sync or holds the radio awake - the foreground kiosk is untouched), `svc data disable` turns mobile data off, and `svc wifi disable` turns Wi-Fi off. A locked screen cannot use the network, so every packet it would have woken for is pure drain.
- The radio state is read **before** anything is flipped and stored, so unlocking restores exactly what the owner had: a device whose owner had data or Wi-Fi off is left off and is never silently turned on.

### Changed
- `Prefs.setLocked()` is now the single funnel for the policy: every path that changes the lock state (the service entering/leaving the lock, a window rollover handled inside the kiosk, `Engine.selfHeal` after an ownership loss) engages or releases the device-wide power policy, so it can never be left engaged on an idle device or skipped on a locked one. `BatteryGuard.sync()` is a cheap in-memory read that returns **without spawning anything** when the policy already matches the lock state, and the actual shell work rides on one short-lived daemon thread per real transition (never per tick, no timer, no alarm, no polling loop and no wakelock).

### Notes
- Everything is defensive: a missing binder, a revoked permission or a `RemoteException` degrades to one recorded note and changes nothing device-wide, and a reboot is handled (`staleSinceBoot`), so the pass re-engages after a restart because a reboot resets the radios to their defaults.
- Version bumped from 7.0 (versionCode 32) to 7.1 (versionCode 33), signed with the maintainer private release key.

## 7.0 - 2026-09-26

### Added
- **Interactive display-brightness card on the kiosk lock screen.** The dashboard gains its first input surface: a `DISPLAY BRIGHTNESS` card, sitting between the stat tiles and *Recent activity*, with an **Auto brightness** switch, a **MANUAL LEVEL** slider (`0`-`255`) and a live whole-percent read-out. Writes go through the new `Brightness` helper, which uses the Device-Owner `DevicePolicyManager.setSystemSetting` path - the lock screen only ever runs as the Device Owner - so the panel level and the adaptive-brightness mode are set with **no runtime permission, no `WRITE_SETTINGS` app-op and no Shizuku round-trip**, and the change is device-wide, taking effect outside the kiosk once the lock lifts.
- The adaptive state and manual level are read on each tick from `Settings.System` and carried on `KioskSnapshot` (`brightnessAuto`, `brightnessLevel`), so the controls stay in step with a change made elsewhere - or one that survives a reboot - instead of showing a stale value.

### Changed
- `KioskDashboard` gains the only `OnCheckedChangeListener` / `OnSeekBarChangeListener` in the module. They fire **only on a real user gesture**, never on the 1 Hz `bind`: a programmatic mirror is flagged (`syncingBrightness`) so syncing a control to device state is never echoed back as a write, and a control being touched is left alone until the gesture ends. Setting a manual level leaves adaptive mode first (the mode is written before the level), and the switch is corrected without a second write.

### Notes
- **The zero-wake, zero-battery render contract is unchanged.** The card adds two `Settings.System` reads per tick and no timer, alarm, receiver or wakelock; every rendered value is still diffed before it is written, so a steady brightness performs no invalidation.
- Reads and writes are defensive: a vendor HAL that refuses the write leaves the panel exactly as it was rather than throwing into the render path, and an unreadable level renders `--` rather than a false `0%`.
- Version bumped from 6.10 (versionCode 31) to 7.0 (versionCode 32), signed with the maintainer private release key.

## 6.10 - 2026-09-26

### Added
- **Battery percentage in the kiosk lock screen's top-right corner.** While the kiosk (lock) mode is active, the lock screen now shows the charge level as a small pill in the top-right corner - `82%`, or `\u26A1 82%` while the device is charging - tinted by level (muted above 30%, amber at 30% or below, red at 15% or below). It is painted on both the live lock screen (`KioskDashboard`) and the brief direct-boot (pre-unlock) projection (`LockActivity`), so the corner is never blank from the instant the framework is up.

### Notes
- The level is read once per tick from the STICKY `ACTION_BATTERY_CHANGED` broadcast via `registerReceiver(null, ...)`, which returns the cached Intent **without registering a live receiver**. No `BroadcastReceiver`, `BatteryManager` listener, wakelock or self-scheduled timer is added, so the render path keeps its zero-wake, zero-battery contract. The value rides on the existing `KioskSnapshot` (`batteryPct`, `charging`) like every other number the dashboard paints.
- The read-out is diffed before writing, so a tick that does not change the charge performs no invalidation. When the level is unknown (`-1`) the pill is hidden rather than printing a false `0%`, matching the app-wide no-data sentinel policy.
- Version bumped from 6.9 (versionCode 30) to 6.10 (versionCode 31).

## 6.9 - 2026-09-26

### Added
- **Permanent app-control strip** (`Engine.applyHardening`). Once every permission — Device Admin included — is approved and the commitment is activated, the Device Owner also sets `UserManager.DISALLOW_APPS_CONTROL`. This removes the whole app-management surface (`Settings > Apps`, including every per-app **Permissions** and **Special app access** page) for the entire device, so after activation **no permission can be enabled or disabled for any app**, and no app can be uninstalled, force-stopped or have its data cleared. It is deliberately device-wide — this is a single-purpose commitment/kiosk device — and it is cleared again only when the commitment itself is torn down (`enable == false`). Combined with the package-scoped `setPermissionGrantState(GRANTED)` pin (which already greys out this app's own toggles) it is the hard "permissions cannot be changed afterwards" guarantee.
- **Permission-independent boundary timer** (`TimeLockService`). The engine's primary trigger is a passive exact alarm, but `SCHEDULE_EXACT_ALARM` is a user-revocable *Special app access* toggle and `USE_FULL_SCREEN_INTENT` likewise. When that access is absent the exact arm degrades to an inexact one and the lock boundary could fire late while the panel stays on. The service (already a foreground service, so its main looper is precise while the screen is on) now arms ONE in-process single-shot timer at the SAME deadline whenever `AlarmManager.canScheduleExactAlarms()` is false. It is posted only while the panel is interactive and cleared on screen-off, lock and destroy, so the normal (permitted) path keeps the zero-Handler idle-power model unchanged. Result: the lock still engages **instantaneously even when the exact-alarm permission has been revoked** (and, in principle, all of them).

### Changed
- `build.sh` gained optional per-variant version identity: `VERSION_NAME` / `VERSION_CODE` are passed through to `aapt2 link --replace-version`, so one source tree ships both the private-key **release** build and the public-test-key **GitHub** build with distinct version identities. `OUT_NAME` (already supported) names the output.

### Notes
- **Release build** is version **6.9** (versionCode 30), signed with the maintainer private release key.
- **GitHub build** (`timelock-locker-github.apk`) is version **6.3.3** (versionCode 23), signed with the bundled public test key.
- No new wake lock, thread or alarm was added: the boundary fallback is a single `Handler` callback that lives only while the screen is on and only when exact alarms are unavailable.

## 6.3.6 - 2026-09-26

### Fixed
- **Retreat undercount at the daily reset boundary (screen-off data loss).** When the screen went off *after* the window had rolled over, `TimeLockService.onScreenOff()` rolled the window and then called `UsageStore.endSession()`, which silently **discarded** the entire live session - including every post-boundary minute of screen-on time. `Engine.rollover()` already re-anchors a session that straddled the boundary to the new window start, so the correct behaviour is to bank the session unconditionally. `onScreenOff()` now always calls `UsageStore.bankSession()` after `rollover()`, recording the whole session when no boundary was crossed and only the post-boundary slice when one was.
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
