# libs/

Bundled third-party classes so the APK can talk to Shizuku without Gradle or a runtime download.

| File | Contents |
|---|---|
| `shizuku-api.jar` | Shizuku client API (`rikka.shizuku.*`) |
| `shizuku-aidl.jar` | Shizuku AIDL interfaces |
| `shizuku-shared.jar` | Shizuku shared helpers |
| `shizuku-provider.jar` | The `ShizukuProvider` binder provider |

These are extracted from the Shizuku 13.1.5 AARs by [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku), licensed under Apache 2.0, and compiled into the dex so the client and the binder provider both ship inside the APK.

Shizuku is used for one thing: running `pm list users`, `pm remove-user` and `dpm set-device-owner` with shell privilege so the app can claim Device Owner without a PC or cable. Enforcement always uses genuine `DevicePolicyManager` APIs, never Shizuku.
