# Troubleshooting

## Build

**Android SDK not found.** Set the path explicitly: `ANDROID_SDK_ROOT=/path/to/android-sdk bash build.sh`. Common locations are `/usr/lib/android-sdk`, `$HOME/Android/Sdk` and `$HOME/Library/Android/sdk`.

**aapt2 not found under build-tools.** Your SDK's `build-tools` folder has no usable version, or an extra non-version directory is present. Force one with `BUILD_TOOLS=36.0.0 bash build.sh`.

**"failed to load include path, android.jar" during `aapt2 link`.** Some SDK installs ship a preview platform whose `android.jar` `aapt2` cannot load. The script prefers a stable `android-36` or the highest numeric `android-NN`. Check what you have with `ls $ANDROID_SDK_ROOT/platforms`.

**kotlin-stdlib.jar not found.** Install Kotlin, or pass `KOTLIN_STDLIB=/path/to/kotlin-stdlib.jar`.

**javac, kotlinc or zip not found.** Install a JDK, Kotlin and `zip`. On Debian or Ubuntu: `apt-get install -y default-jdk kotlin zip android-sdk`.

**Duplicate class errors for `OnboardingActivity`.** The project ships both a Java and a Kotlin copy on purpose. Kotlin wins, and the build script leaves the Java one out of `javac`. Do not change that.

## Device

**Setup will not pass the Device Admin step.** The usual cause is accounts still on the device. Check with `adb shell pm list users` and `adb shell dumpsys account`, remove secondary users and all accounts, reboot and retry. If you use Shizuku, make sure its service is running.

**`dpm set-device-owner` says "not allowed, already set up".** The device is not in a provisionable state. Factory reset, or remove all accounts and reboot, then do not sign in to anything.

**The lock did not come back after a reboot.** Check the receiver with `adb shell dumpsys package com.vortex.timelock | grep -i directBoot`. Confirm notifications and the battery exemption were granted and that the app is still Device Owner. Reboot once more with the charger disconnected, since some manufacturers treat charge-only boots specially.

**The OEM battery manager killed the app.** Some vendors kill background apps aggressively. The battery exemption and the autostart whitelist, both handled in the gatekeeper, keep it alive. Re-check them after any OS update, because updates sometimes reset them.

**Counted time looks wrong.** Only screen-on time inside the current window counts, and changing the anchor moves the window start with it. Review it in the console at `*#*#84635#*#*` plus fingerprint.

**Data or Wi-Fi turned off by itself.** That is the lock-mode power policy doing its job while locked: a locked screen cannot use the network. Both radios are restored to their prior state the moment the lock lifts. If they remain off after unlock, check the status card in the console.

**The screen blinks or random apps open.** This happens when an app makes itself a launcher without being the owner. This build registers the kiosk HOME only while a lock is genuinely active and the app is the real Device Owner. If you see it, the app is not actually Device Owner, so check the console status card.

## Removing the app

A normal uninstall is blocked by design. There is no `adb` command here that removes or disables the lock. The only revert is a factory reset, and only if *Block factory reset* was left off. If it was enabled, the device must be re-flashed. See the removal section of [INSTALLATION.md](INSTALLATION.md).

## Still stuck

Open an issue with the device model, Android version and manufacturer; whether you are Device Owner; the exact steps; what you expected versus what you saw; and logcat from `adb logcat -s TL.Engine` if useful. Redact device identifiers.
