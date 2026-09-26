# libs/

Bundled third-party classes so the APK can talk to Shizuku without Gradle or a runtime download.

| File | Contents |
|---|---|
| `shizuku-api.jar` | Shizuku client API (`rikka.shizuku.*`) |
| `shizuku-aidl.jar` | Shizuku AIDL interfaces |
| `shizuku-shared.jar` | Shizuku shared helpers |
| `shizuku-provider.jar` | The `ShizukuProvider` binder provider |

These are extracted from the Shizuku 13.1.5 AARs by [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku), licensed under Apache 2.0, and compiled into the dex so the client and the binder provider both ship inside the APK.

Shizuku is used only for one-time setup and optional hardening, never for enforcement:

- `pm list users` and `pm remove-user` while preparing the device, and `dpm set-device-owner` so the app can claim Device Owner without a PC or cable.
- When available, the optional shell-level hardening pass (`ShizukuHardener`) that pins battery-optimization, autostart and app-op states the `DevicePolicyManager` layer cannot set, and the lock-mode power policy (`BatteryGuard`).

Enforcement itself always uses genuine `DevicePolicyManager` APIs, never Shizuku.
