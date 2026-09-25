# Security Policy

## Scope

Screen Time Locker deliberately uses powerful Android features. It can become Device Owner and enforce a non-bypassable kiosk lock. That is the product, not a vulnerability. Please do not report "hard to uninstall" or "there is no unlock" as security issues. Those are documented design choices, explained in [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md).

What is in scope:

- A way to defeat the lock that the design says should not exist.
- Escaping the kiosk before the accounting window ends.
- Reading or reconstructing an admin credential. There should be none stored.
- Privilege escalation, code execution, or a crash that wedges the OS through a privileged `DevicePolicyManager` call.
- Leaking signing material or other secrets from the repository or the build.
- Supply-chain problems in the bundled `libs/*.jar`.

## Supported versions

The latest release on `main` is supported. There is no back-port policy. This is a small project.

## Reporting a vulnerability

Please do not open a public issue. Instead email the maintainer, or use GitHub's private Report a vulnerability feature on the repository's Security tab. Include the affected version, steps to reproduce, expected against actual behaviour, and any proof of concept. Redact your own device identifiers. We aim to reply within a few days. This is a volunteer project, so thanks for your patience.

## About the signing key

The keystore in `keystore/` is a public test key. It is not a secret, and its being public is not a vulnerability. It exists so anyone can build. Real releases need a private key you control. See the README's test signing key section.

## Safety warning

Device Owner needs a factory-fresh or account-free device and makes the app hard to remove. Test on a spare device. You are responsible for the device you lock.
