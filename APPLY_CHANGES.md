# Applying the economy slice

Prepared against `main` at commit `378dc3a0ba884490f44a0b954eb4456b9f5bd926` (character profile + SQLite schema version 2).

1. Make a backup/commit of your local working project.
2. Extract this archive, then copy its contents into `C:\Users\skviz\IdeaProjects\HRPCoreMod`, preserving the directory structure and replacing the matching files.
3. Files replaced:
   - `src/main/java/ru/hrp/core/HrpProfileStorage.java`
   - `src/main/java/ru/hrp/core/HrpProfileService.java`
   - `src/main/java/ru/hrp/core/network/HrpNetwork.java`
   - `src/client/java/ru/hrp/client/HRPCoreClient.java`
   - `src/client/java/ru/hrp/client/screen/HrpMainScreen.java`
   - `README.md`
   - `ARCHITECTURE.md`
4. New file:
   - `src/client/java/ru/hrp/client/HrpFinanceState.java`
   - `src/client/java/ru/hrp/client/screen/HrpFinanceScreen.java`
5. Do not replace `build.gradle`, `gradle.properties`, `fabric.mod.json`, `HrpEditProfileScreen.java` or `PanelWidget.java`.
6. Run ` .\gradlew.bat build ` from PowerShell.
7. If the build succeeds, run ` .\gradlew.bat runClient `.
8. Join the same world, press **O**, and open **ФИНАНСЫ**. Test deposit and withdrawal, then reopen the world and verify that balances and history persist.

## Migration behavior

The schema advances from version 2 to version 3. The existing player profile table is not dropped or recreated. The migration adds `finance_accounts` and `finance_transactions`, seeds each existing profile with a one-time 1,000.00 ₽ starting wallet, and writes `PRAGMA user_version = 3`. New player profiles receive the same starter wallet only when the finance account is first inserted.

Amounts are transmitted and stored as integer kopecks. The server validates every action. The finance action codes are 0 = deposit, 1 = withdraw, 2 = transfer to a Minecraft username. The client never specifies a balance or UUID to be changed.

This archive has not been run through the user's local Gradle environment. The first verification step must therefore be `build`; if it fails, share the first compiler error before applying additional changes.
