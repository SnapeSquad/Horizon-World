# Applying the profile database migration update

This update adds schema-version tracking to the SQLite profile repository. It is intended for the current project state containing `HrpProfileStorage.java`, `HrpProfileService.java`, and the SQLite JDBC dependency.

1. Back up your project or commit your current work.
2. Extract this archive.
3. Copy files from the extracted `Horizon-World-profile-migrations` folder into `C:\Users\skviz\IdeaProjects\HRPCoreMod`, preserving folder structure and allowing these files to be replaced:
   - `src/main/java/ru/hrp/core/HrpProfileStorage.java`
   - `README.md`
   - `ARCHITECTURE.md`
4. Do not replace `HrpProfileService.java` or `build.gradle` for this update.
5. In PowerShell, run:

   ```powershell
   .\gradlew.bat build
   ```

6. If the build succeeds, run:

   ```powershell
   .\gradlew.bat runClient
   ```

7. Open the same world and check that the existing player profile and play time are still present. The migration uses `CREATE TABLE IF NOT EXISTS` and does not drop or recreate the table.

The update is prepared against the current repository source but has not been run through your local Gradle build. If Gradle reports an error, send the first compiler error before applying unrelated changes.
