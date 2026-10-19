# HRPCore — current implementation

HRPCore is an early Fabric mod for Minecraft Java Edition 1.21.10. This project is a new Fabric implementation; Bukkit/Paper/Vault/PlaceholderAPI APIs are not used here.

## Implemented vertical slice

- Fabric common and client entrypoints.
- `/hrp` server smoke-test command.
- Press **O** to open the HRPCore interface while preserving the vanilla HUD.
- Client requests a profile snapshot; the server is authoritative.
- First join time and accumulated online ticks are stored in SQLite.
- SQLite operations run on a dedicated single-thread executor, not on the normal game tick.
- Online profile snapshots are saved about once per minute, on disconnect, and while the server is stopping.
- SQLite is bundled as a nested library in the mod JAR.

## Requirements

- JDK 21
- Minecraft Java Edition 1.21.10
- Matching Fabric Loader and Fabric API versions from `gradle.properties`

In IntelliJ IDEA, set the Gradle JVM to JDK 21. Build with `./gradlew build` (Windows: `gradlew.bat build`). The mod JAR is written to `build/libs/`.

## Profile database

The database is stored in the active world's folder at `data/hrpcore/profiles.db`. Back up the world folder to back up profile data. Do not edit the database while the server is running.

## Smoke test

1. Run `runClient` and join a world.
2. Press **O** and verify the profile fields appear.
3. Note the displayed first-join date and play time.
4. Leave the world, reopen it, and check that the first-join date and saved play time were retained.
5. Check `logs/latest.log` for `HRPCore profile database ready` and any storage errors.

## Current limitations

Finance, professions, talents, factions, crime, jail, and medical systems are still UI placeholders. Profile storage is the first persisted server-side domain; schema migrations and repositories for the other domains have not been added yet.
