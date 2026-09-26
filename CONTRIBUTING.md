# Contributing to Digital Retreat

Thanks for helping. This project has a specific personality, and the useful contributions respect it.

## The one rule

Never weaken the core promise. Digital Retreat is hard and non-bypassable **on purpose**. Pull requests that add an unlock button, a recovery backdoor, a hidden reset, or any way to escape the lock early will be closed. If you think there should be an escape hatch, that is a different app — fork it.

## What helps

- Bug fixes in the enforcement engine: window math, boot re-arm, alarm handling.
- OEM compatibility fixes, since Device-Owner behaviour varies by vendor.
- Accessibility and translation of the UI strings.
- Clearer documentation. Plain words beat clever words.
- Lower power cost: fewer wakeups, fewer repaints, smaller dex.

## Setup

See [docs/INSTALLATION.md](docs/INSTALLATION.md). The short version:

```bash
git clone https://github.com/emirofcordoba/screentime-locker.git
cd screentime-locker
bash build.sh
```

You need a JDK, Kotlin and the Android SDK command-line tools. No Gradle.

## Style

- The code is heavily commented on purpose. It doubles as teaching material, so explain **why**, not just what.
- Prefer plain Android framework APIs over new dependencies. *No Gradle* is a feature.
- Wrap every privileged `DevicePolicyManager` call so a missing permission can never crash or wedge the OS.
- Never act unless the app is the real Device Owner.

## Pull requests

1. Fork and branch from `main`.
2. Keep the diff focused — one concern per PR.
3. Say whether you tested on a real device or only compiled. Both are fine; just be honest.
4. Update `CHANGELOG.md` under an `Unreleased` heading (or a new version heading if you are cutting a release).

## Security

Do not open a public issue for a security bug. See [SECURITY.md](SECURITY.md).

## Never commit

Real signing keystores or passwords, build output like `build/` and `*.apk`, personal device data or logs, and API tokens or credentials of any kind.
