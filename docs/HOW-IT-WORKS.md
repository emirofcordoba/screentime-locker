# How it works

This page assumes no Android knowledge.

## The short version

The app measures how long your screen is actually on. When you have used your daily allowance, it becomes the phone's Device Owner and puts up a kiosk lock that nothing can dismiss until the next window starts.

## Counting screen-on time

Android broadcasts an event whenever the screen turns on or off, and the app keeps a foreground service listening for both.

- **Screen on:** start a stopwatch and arm one delayed timer for the milliseconds you have left.
- **Screen off:** stop the stopwatch, bank the session and cancel the timer.

The clock only runs while you are looking at the phone, so a device in your pocket costs nothing. That is why the app never polls and never holds a wake lock.

Your allowance resets at a time you choose — say 5 a.m., not midnight. The app works out the current 24-hour window from that anchor, and a session that straddles the boundary is split correctly so no screen-on time is lost.

## Deciding to lock

When screen-on time inside the window reaches your limit, the engine flips to locked. It clears itself when the window ends. A limit of zero never locks, because there is nothing to enforce.

## Becoming Device Owner

An overlay on top of your screen can be swiped away. That is a suggestion, not a lock. A real lock needs the app to be Device Owner — the role a company uses to manage a fleet of phones.

Android only allows this on a device with no user accounts, which is why setup asks you to factory reset or remove every account and reboot. The app cannot skip that rule.

As Device Owner it can:

- Turn on lock-task kiosk mode, where Home, Recents and notifications stop existing.
- Disable the status bar and keyguard while locked.
- Set itself as the persistent preferred HOME app, so a reboot relaunches the lock.
- Freeze its own granted permissions so Settings cannot revoke them.
- Optionally block Safe Mode and factory reset, and disable USB debugging.

It uses none of these unless it is the genuine Device Owner, and every call is wrapped so a missing permission cannot crash the system.

## Surviving a reboot

Restarting the phone is the obvious way to cheat, so the app uses direct boot.

Android splits storage in two. Credential-encrypted storage is readable only after you unlock; device-protected storage is readable before. Normal apps live in the first, which would leave a gap right after a reboot where no lock exists.

This app mirrors the little state it needs (`activated`, `locked`, `lockUntil`) into device-protected storage, and its `BootReceiver` is direct-boot aware. On `LOCKED_BOOT_COMPLETED`, before you have typed your passcode, it re-asserts the Device-Owner policy and reinstalls the HOME override. Because `LockActivity` is itself direct-boot aware, the kiosk is drawn from that mirror the instant the framework is up — the lock is back by the time you unlock.

No OEM autostart permission is needed for this, which matters because those vendor toggles are unreliable and often hidden.

## A quiet phone while it is locked

A locked screen cannot use the network, so every packet it would have woken for is pure drain. When the lock engages, the app also applies a small power policy: it stops background user apps and turns mobile data and Wi-Fi off. On unlock it restores exactly what you had — if data or Wi-Fi were already off, they stay off.

The radio state is read *before* anything is flipped, so nothing is silently switched on behind your back.

## The admin console

After setup the configuration is sealed and the app hides from your launcher. To change anything, dial `*#*#84635#*#*` from the phone app and pass a fingerprint check.

There is no PIN, no password and no recovery phrase, and nothing is stored. Identity is proven by the platform's biometric stack, so there is nothing to steal or social-engineer.

## Why there is no escape hatch

An escape hatch is the whole problem with other screen-time apps. If you can disable it in four taps, you will, at 2 a.m. See [PHILOSOPHY.md](PHILOSOPHY.md).

## Optional hardening

All off by default, all opt-in at setup:

- **Block Safe Mode**, so safe mode cannot sidestep the app.
- **Block factory reset**, the nuclear option.
- **Disable USB debugging**, which closes the `adb` route.
- **Freeze granted permissions** so they cannot be revoked.

Turn these on only once you are sure the lock behaves on your device.
