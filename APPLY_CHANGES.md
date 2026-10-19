# Applying the RP character profile update

This is an overlay package for the current Horizon-World `main` state with schema version 1 and `HrpProfileStorage`/`HrpProfileService` already present.

1. Back up the project or commit the current working version.
2. Extract the archive.
3. Copy the contents of the extracted `hrpcore-character-profile` folder into `C:\Users\skviz\IdeaProjects\HRPCoreMod`, preserving the folder structure and allowing these listed files to be replaced:
   - `src/main/java/ru/hrp/core/HrpProfileStorage.java`
   - `src/main/java/ru/hrp/core/HrpProfileService.java`
   - `src/main/java/ru/hrp/core/network/HrpNetwork.java`
   - `src/client/java/ru/hrp/client/HRPCoreClient.java`
   - `src/client/java/ru/hrp/client/HrpProfileState.java`
   - `src/client/java/ru/hrp/client/screen/HrpMainScreen.java`
   - `README.md`
   - `ARCHITECTURE.md`
4. Add the new file `src/client/java/ru/hrp/client/screen/HrpEditProfileScreen.java` from the archive.
5. Do **not** replace `HRPCore.java`, `build.gradle`, `gradle.properties`, or `fabric.mod.json` for this update.
6. In PowerShell from the project root, run:

   ```powershell
   .\gradlew.bat build
   ```

7. If it succeeds, run:

   ```powershell
   .\gradlew.bat runClient
   ```

8. Join the same world, press **O**, select **Редактировать персонажа**, and save a new RP name, optional character birth date, and biography. Reopen the profile and restart/rejoin the world to confirm the values persisted.

## Migration behavior

The storage code advances `PRAGMA user_version` from 1 to 2 and adds the three RP columns. Existing UUIDs, first-join times, and play-time ticks are retained. Existing records receive their current Minecraft account name as their initial RP name.

This archive was prepared against the inspected source, but the local Gradle build must still be run in the target project. If there is a compiler error, send the first error and the surrounding `What went wrong` output before making unrelated edits.
