# Changelog

Notable changes to Screen Time Locker, following [Keep a Changelog](https://keepachangelog.com/en/1.1.0) and [Semantic Versioning](https://semver.org/).

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
