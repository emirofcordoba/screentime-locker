# FAQ

## General

**Is this a wellbeing or habit app?** No. It sets a rule and enforces it. It does not coach you. See [PHILOSOPHY.md](PHILOSOPHY.md).

**Do I need root?** No. It uses the standard Android Device-Owner mechanism.

**Do I need a computer?** Not always. With [Shizuku](https://shizuku.rikka.app/) the app grants itself Device Owner. Without it you need `adb` once.

**Is there an iOS version?** No. The approach depends on Android's Device-Owner APIs.

**Is my data sent anywhere?** No. There is no network code and no analytics. Everything stays on the device.

## Setup

**Why must I factory reset or remove all accounts?** Android only permits Device Owner on a device with no accounts. The app cannot bypass that.

**Can I add a Google account later?** You can, but accounts can affect future provisioning and updates. For a lock you plan to keep, stay account-free.

**A step won't pass.** Each step re-checks when the screen resumes. Make sure the step really succeeded, for example that the battery exemption is actually granted, then return to the app. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

**What does the secret code do?** `*#*#84635#*#*` opens the admin console. A fingerprint check still stands behind it.

## Usage

**How is time counted?** Only while the screen is physically on, inside your window. Screen off pauses it.

**When does the allowance reset?** At the anchor you chose, every day. The window is the 24 hours starting at that anchor.

**Why a 24-hour window?** Because it is a daily budget, not a token you can refill.

**Can each day have its own limit?** Yes. The schedule module gives every weekday its own limit, or turns it off.

**What if I reboot during a lock?** The direct-boot receiver re-arms the lock before you unlock, so it is still there.

**The status bar says "Today no lock!".** That means no limit is set yet, or you are outside a locked window. It is telling you the truth instead of a misleading `00:00:00`.

## Escape and removal

There is no temporary off-switch. No timer to wait out, no unlock anyway, and once the app is Device Owner no `adb` command undoes it. The app never exposes `clearDeviceOwnerApp()`, so there is nothing for a tool to call. The single route off is a factory reset, and only if you left *Block factory reset* off.

**Where is the emergency unlock?** There isn't one, on purpose. A soft lock you can dismiss at 2 a.m. is a suggestion, and suggestions lose.

**How do I remove the app?** One way only, a factory reset. A normal uninstall is blocked because the app is Device Owner, and the settings are sealed after setup.

**Does the factory reset work only if the toggle was off?** Correct. If you enabled *Block factory reset*, that door is closed too and the only way back is to re-flash the device. Turn that on only when you are certain.

**Can someone else remove it?** Not by hand. Not the owner, not a family member, not a thief. A factory reset is the only route, and only while it is not blocked.

## Security

**Does it store my password or PIN?** No. There is no stored secret. The console uses your device biometrics.

**Could malware abuse the pinned permissions?** The pinning is scoped to this app. The app deliberately avoids device-wide permission policy to keep the blast radius small.

**Is the test key in the repo a problem?** No. It is meant to be public. Just do not ship real releases with it.

## Building

**Why no Gradle?** It keeps the project tiny, dependency-free and readable for students learning how Android builds work.

**The build cannot find the SDK.** Set `ANDROID_SDK_ROOT=/path/to/sdk` and retry.

**It fails on a platform `android.jar`.** Force a known-good platform and build-tools, for example `BUILD_TOOLS=36.0.0`. See [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
