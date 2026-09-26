# Installation

Two ways in: install the prebuilt APK, or build it yourself. Both end at the same four-step setup.

## Install the prebuilt APK

1. On your phone, open the repo's Releases page and download the latest `timelock-locker.apk`.
2. Allow your browser or file manager to install unknown apps.
3. Open the APK and install it.
4. Launch Screen Time Locker.

The prebuilt APK is signed with the maintainer's private release key (not the bundled test key). That is fine for personal use. See the README if you plan to publish your own builds.

## Build it yourself

You need a JDK (17 or newer recommended, the build targets Java 8 bytecode), Kotlin on your `PATH`, and the Android SDK command-line tools providing `aapt2`, `d8`, `zipalign` and `apksigner`, plus at least one platform `android.jar`. No Gradle, no Android Studio, and no internet during the build.

```bash
git clone https://github.com/emirofcordoba/screentime-locker.git
cd screentime-locker
bash build.sh
```

The script finds the SDK at `ANDROID_SDK_ROOT`, `$HOME/Android/Sdk`, `$HOME/Library/Android/sdk` or `/usr/lib/android-sdk`. Override it if needed:

```bash
ANDROID_SDK_ROOT=/path/to/sdk BUILD_TOOLS=36.0.0 bash build.sh
```

The output is `build/timelock-locker.apk`, signed with v2 and v3 and aligned. Install it:

```bash
adb install -r build/timelock-locker.apk
```

To sign with your own key, pass the keystore through the environment:

```bash
SIGNING_STORE=$HOME/keys/my-release.jks \
SIGNING_PASS='your-password' \
SIGNING_ALIAS=mykey \
bash build.sh
```

Make a key if you do not have one:

```bash
keytool -genkeypair -keystore my-release.jks -alias mykey \
  -keyalg RSA -keysize 4096 -validity 10950 -storetype PKCS12
```

## Prepare the device

Android only lets an app become Device Owner on a device with no accounts:

1. Factory reset the phone, or remove every account and turn off auto-sync.
2. Reboot.
3. Do not sign in to anything afterwards, including a Google account.

The app shows this as a warning and will not let you continue until you acknowledge it.

## The four-step gatekeeper

On first launch the app walks four items in order. Each button opens one system screen, and the next unlocks only when the previous is confirmed.

| Step | What it does | Where it opens |
|---|---|---|
| Autostart | Lets the app restart after reboot | Your phone maker's autostart settings |
| Notifications | Shows the countdown and lock alerts | The standard notification prompt |
| Battery | Stops the system pausing the background guard | The battery exemption dialog |
| Device Admin | Lets the app enforce the lock | Shizuku, or the `adb` command shown |

For the last step, either use Shizuku (no PC, it runs `dpm set-device-owner` for you) or run this from a computer:

```
adb shell dpm set-device-owner com.vortex.timelock/.TimeLockAdmin
```

## Configure and seal

Choose your daily screen-on limit, the time the counter resets, and the warning seconds before the screen turns off. Optionally add per-weekday limits or extra hardening. Confirm through the two-step dialog and the configuration is sealed. After that the console is reachable only through `*#*#84635#*#*` plus a fingerprint.

## Removing the lock

Read this before installing.

Because the app is Device Owner, a normal uninstall is blocked. Settings cannot force-stop it, clear its data or remove it. There is no in-app release button, no recovery code and no PIN. The app never exposes `clearDeviceOwnerApp()`, so there is nothing for a tool to call.

This project documents no `adb` command that disables or reverts the lock, because once the app owns the device none exists. The only `adb` commands here are the one-time grant during setup and ordinary `adb install`.

The one and only route off a locked device is a factory reset, and it works only if you left the optional *Block factory reset* toggle off. If you turned it on, even the factory reset is closed and the device must be re-flashed.

So install this only on a device you are willing to factory reset, and test on a spare device first. This is intended behaviour, not a bug. See [PHILOSOPHY.md](PHILOSOPHY.md).
