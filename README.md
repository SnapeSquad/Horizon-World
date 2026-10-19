# HRPCore — current implementation

HRPCore is an early Fabric mod for Minecraft Java Edition 1.21.10. Bukkit/Paper/Vault/PlaceholderAPI APIs are not used in this Fabric module.

## Implemented

- `/hrp` server smoke-test command.
- Press **O** to open the HRPCore interface while preserving the vanilla HUD.
- Client requests a server-authoritative profile snapshot.
- First join time and accumulated online ticks are stored in SQLite.
- SQLite operations run on a dedicated single-thread executor, not on the normal game tick.
- Online snapshots are saved approximately once per minute, on disconnect, and during orderly server shutdown.
- SQLite JDBC is bundled as a nested library in the mod JAR.
- Database schema versioning uses SQLite `PRAGMA user_version`; an existing unversioned profile table is migrated in place without dropping its rows.

## Requirements

- JDK 21
- Minecraft Java Edition 1.21.10
- Matching Fabric Loader and Fabric API versions from `gradle.properties`

In IntelliJ IDEA, set the Gradle JVM to JDK 21. Build with `./gradlew build` (Windows: `gradlew.bat build`). The mod JAR is written to `build/libs/`.

## Profile database

The database is stored at `<world>/data/hrpcore/profiles.db`. Back up the world folder to back up profile data. Do not edit the database while the server is running. Schema upgrades are applied by the storage layer when the database is opened.

## Smoke test

1. Run `runClient` and join a world.
2. Press **O** and verify the profile fields appear.
3. Note the displayed first-join date and play time.
4. Leave the world, reopen the same world, and verify the first-join date and saved time remain.
5. Check `logs/latest.log` for `HRPCore profile database ready` and any storage errors.

## Current limitations

Finance, professions, talents, factions, crime/jail, medical status, and player-editable roleplay fields remain unimplemented. Profile storage is the first persisted server-side domain.
