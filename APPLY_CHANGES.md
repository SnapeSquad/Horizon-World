# Applying this update

This archive contains a set of files to overlay onto the current `Horizon-World` repository. It was prepared against the repository's `main` state inspected on 2026-10-19, but it could not be committed remotely because the connected GitHub integration denied write access.

1. Back up your local project or commit your current work.
2. Extract the archive somewhere convenient. Open the `Horizon-World-profile-persistence` folder inside it, then copy that folder’s contents (including `src`, `build.gradle`, `README.md`, and `ARCHITECTURE.md`) into `C:\Users\skviz\IdeaProjects\HRPCoreMod`. Allow matching files to be replaced. Do not copy the outer folder itself into the project.
3. In IntelliJ, wait for Gradle sync to finish.
4. Run ` .\gradlew.bat build `.
5. If it succeeds, run ` .\gradlew.bat runClient ` and verify the profile persists after leaving and reopening the same world.

Files included:
- `build.gradle` — adds and bundles SQLite JDBC.
- `src/main/java/ru/hrp/core/HrpProfileStorage.java` — new SQLite repository.
- `src/main/java/ru/hrp/core/HrpProfileService.java` — async storage lifecycle and profile tracking.
- `src/client/java/ru/hrp/client/HRPCoreClient.java` — removes duplicate imports and keeps client state updates on the client thread.
- `src/client/java/ru/hrp/client/screen/HrpMainScreen.java` — clarifies that profile data comes from the server.
- `README.md`, `ARCHITECTURE.md` — updated project notes.

The code still needs to pass your local Gradle build and runtime smoke test. If Gradle reports a specific error, send the first `What went wrong` section and the relevant `Caused by` lines before applying further changes.
