# Applying the HRPCore economy-admin update

Prepared as an overlay against the working Economy v1 + Character Profile + SQLite schema v3 code.

1. Back up or commit the local project first.
2. Extract this archive and copy the contents of `Horizon-World-economy-admin` into `C:\Users\skviz\IdeaProjects\HRPCoreMod`, preserving folder structure and allowing the listed existing files to be replaced.
3. Existing files replaced:
   - `src/main/java/ru/hrp/core/HRPCore.java`
   - `src/main/java/ru/hrp/core/HrpProfileService.java`
   - `src/main/java/ru/hrp/core/HrpProfileStorage.java`
   - `README.md`
   - `ARCHITECTURE.md`
4. New file:
   - `src/main/java/ru/hrp/core/HrpAdminCommands.java`
5. Do not replace `build.gradle`, `gradle.properties`, `fabric.mod.json`, `HrpNetwork.java`, or any client GUI files.
6. No database schema change is needed. Admin actions reuse `finance_transactions`, so the database remains on schema version 3.
7. In PowerShell, run ` .\gradlew.bat build `. If it succeeds, run ` .\gradlew.bat runClient `.

## Commands to test in a world where you have operator permission

- `/hrp admin balance YourMinecraftName`
- `/hrp admin give YourMinecraftName 25,50 testing give`
- `/hrp admin take YourMinecraftName 5,50 testing take`
- `/hrp admin givebank YourMinecraftName 30 testing bank give`
- `/hrp admin takebank YourMinecraftName 10 testing bank take`
- `/hrp admin history YourMinecraftName`

Admin adjustments require a known player name, positive amount, no more than 100,000,000.00 ₽ per operation, and only up to two decimal places. The balance adjustment and matching history entry commit together. Giving and taking bank funds do not alter cash; `give` and `take` only affect cash.

This archive is a prepared patch. Run the full Gradle build locally before considering the changes verified. If a compilation error appears, send the first compiler error and its surrounding context.
