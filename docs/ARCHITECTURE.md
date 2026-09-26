# Architecture

Screen Time Locker is small on purpose. Everything is plain Android framework code — no Gradle and no third-party runtime dependency, apart from the bundled Shizuku client used to grant Device Owner without a PC and to run the optional shell-level hardening.

Package `com.vortex.timelock`, app label **Screen Time Locker**.

## Layers

Five rough layers. Lower layers never reach upward.

| Layer | Contains |
|---|---|
| UI | Gatekeeper, Setup, Status, Lock, Dashboard |
| Consent | `Approval*`, the two-step confirmation ladders |
| Enforcement | `Engine`, `Enforcer*`, `PowerGovernor`, `BatteryGuard`, ticker |
| State | `Prefs`, `UsageStore`, `BootState`, schedules |
| Platform bridges | `ShizukuBridge`, `ShizukuHardener`, `TimeLockAdmin`, `Brightness`, biometrics |

## Enforcement

| Class | Role |
|---|---|
| `Engine` | Window math, lock decisions and hardening. Acts only as the real Device Owner. |
| `TimeLockService` | Foreground service holding the screen-on ledger. Arms one delayed callback for the exact time left. |
| `EnforcerScheduler` | Uses the platform's `JobScheduler` instead of custom loops. |
| `EnforcerReceiver` | Alarm and watchdog receiver — passive non-wakeup RTC triggers only. |
| `EnforcerJobService` | One cheap synchronous reconciliation per run. No threads, no wake locks. |
| `PowerGovernor` | Zero work while the device is not interactive, at most one unit when it is. |
| `RestrictionGuard` | Guards the two root-level restrictions, Safe Mode and factory reset. |
| `WakeGuard` | Keeps the lock surface awake during a hard lock. |
| `BatteryGuard` | Lock-mode power policy: stops background apps and switches radios off while locked, restores the exact prior radio state on unlock. |
| `ShizukuHardener` | Shell pass that pins battery-optimization, autostart and app-op states the `DevicePolicyManager` layer cannot set. |

## State

| Class | Role |
|---|---|
| `Prefs` | Durable store in `tl_state`. Mutating writes use `commit()` so a reboot cannot lose accounting data. `setLocked()` is the single funnel that also drives the power policy. |
| `UsageStore` | Single source of truth for the counter and window start. |
| `BootState` | Device-protected mirror of the small pre-unlock state slice. |
| `ScheduleConfig` | Per-weekday rules carved out of the daily interval. |
| `ConfigHistory` | Configuration audit trail. |
| `DbMigration` | Schema migration and first-boot reconfiguration. There is no SQLite database; the Prefs file is the database. |

## Reboot and admin

| Class | Role |
|---|---|
| `BootReceiver` | Direct-boot aware. Pre-unlock it touches only `BootState` and re-asserts policy; post-unlock it runs the full engine. |
| `NotificationGuardReceiver` | Re-asserts the foreground state if the notification is swiped away. |
| `PowerStateReceiver` | Exported=false receiver on `DEVICE_IDLE_MODE_CHANGED` / `POWER_SAVE_MODE_CHANGED`, so permission permanence is re-asserted the instant the Doze or Battery-Saver posture changes. |
| `TimeLockAdmin` | The `DeviceAdminReceiver`. Receives ownership and supports transfer. |

## Screens

| Class | Role |
|---|---|
| `SplashActivity` | Cold-start frame while state loads. |
| `GatekeeperActivity` | The only `MAIN` and `LAUNCHER` entry. Pins the warning and walks autostart, notifications, battery and Device Admin in order. Back does nothing and CONTINUE stays disabled until all four pass. |
| `PermissionActivity`, `PermissionFlow` | Per-permission onboarding, each one approved by a separate tap. |
| `MainSetupActivity`, `TransitionGate` | Transition screens that auto-advance once the prerequisites verify. |
| `OnboardingActivity` | Warning banner and steps container. Ships in Java and Kotlin; the Kotlin copy wins. |
| `SetupActivity` | The admin console: interval, reset time, warning seconds, behaviour, protections. Reached only by dial code plus biometrics. |
| `StatusActivity` | Always-available read-only status screen. Every notification opens it by explicit component. Never mutates a preference. |
| `LockActivity` | The kiosk lock. Blocks Back, Home and Recents and disables the status bar and keyguard. Direct-boot aware so it re-arms pre-unlock. |
| `LauncherDashboard` | Read-only remaining-time view. Never mutates a preference. |
| `KioskContainer`, `KioskDashboard`, `KioskSnapshot` | The lockdown surface and the readable projection of the kiosk state used by every tick. |
| `KioskLogEvent`, `KioskLogParser`, `KioskLogStore` | The kiosk activity log and its readable projection. |
| `IntervalSelector`, `WeekdayScheduleCard`, `SheetDialog`, `TimeFmt`, `Ui` | Reusable UI, the wall-clock formatter and the light and dark design-token layer. |

## Consent

`ApprovalGate` fronts two tiered approval ladders. `ApprovalCatalogue`, `ApprovalCheckpoint`, `ApprovalSequence`, `ApprovalWalk` and `ApprovalTrigger` make up the strict two-step dialogs, which Back or an outside tap cannot cancel. `ApprovalLedger` and `ApprovalState` keep a tamper-evident, device-bound record.

## Platform bridges

| Class | Role |
|---|---|
| `ShizukuBridge` | The only place the app talks to Shizuku. A thin defensive wrapper over the bundled client. |
| `ShizukuShell` | Kotlin counterpart of the Shizuku shell path. |
| `Brightness` | Device-Owner `setSystemSetting` writes for the kiosk brightness control. No runtime permission, no app-op. |
| `BiometricAuth` | Native biometric identity. No stored secret of any kind. |
| `SecretCodeReceiver` | Hidden admin doorbell at `*#*#84635#*#*`. A doorbell, not a bypass, since the biometric check still stands behind it. |
| `CountdownTicker`, `HardwareTick`, `LiveCountdown`, `VsyncFrameClock` | The 1 Hz tick handler and the status-bar countdown optimiser. |

## Rules that protect the device

1. Never act unless we are the real Device Owner.
2. Never hijack the screen or register as a launcher except while a lock is genuinely active and we are the owner. This is what prevents the classic blinking screen and random app launches.
3. Wrap every privileged call so a missing permission cannot crash or wedge the OS.
4. No background spin. All timers are passive non-wakeup alarms plus `JobScheduler`.

## Build pipeline

`build.sh` runs `aapt2 compile`, `aapt2 link`, `javac`, `kotlinc`, `d8`, zip the dex, `zipalign` and `apksigner`. The Kotlin tree supersedes its Java `OnboardingActivity` twin, so that one Java file is left out of `javac` and compiled by `kotlinc` instead.
