# HRPCore — current implementation

HRPCore is an early Fabric mod for Minecraft Java Edition 1.21.10. Bukkit/Paper/Vault/PlaceholderAPI APIs are not used in this Fabric module.

## Implemented

- `/hrp` server smoke-test command.
- Press **O** to open the HRPCore interface while preserving the vanilla HUD.
- Server-authoritative profile snapshots sent to the client.
- First join time and accumulated online ticks persisted in SQLite.
- Player-editable RP display name, fictional character birth date, and short biography.
- All editable fields are validated on the server; clients cannot select another player's UUID.
- SQLite work is serialized on a dedicated single-thread executor.
- Snapshot saves run approximately once per minute, on disconnect, and during orderly server shutdown.
- SQLite JDBC is bundled in the mod JAR.
- Sequential schema migrations use SQLite `PRAGMA user_version`; existing profile rows are migrated in place.

## Requirements

- JDK 21
- Minecraft Java Edition 1.21.10
- Fabric Loader and Fabric API versions listed in `gradle.properties`

In IntelliJ IDEA, set the Gradle JVM to JDK 21. Build on Windows with:

```powershell
.\gradlew.bat build
```

The mod JAR is written to `build/libs/`.

## Profile database

Database path: `<world>/data/hrpcore/profiles.db`. Back up the world folder to back up profile data. Do not edit the database while the server is running.

Schema versions:

- Version 1: account name, first-join timestamp, and accumulated online ticks.
- Version 2: adds `rp_name`, `birth_date`, and `biography` without dropping existing profile rows.

## Smoke test

1. Run `runClient` and join a world.
2. Press **O** and confirm that the profile loads.
3. Select **Редактировать персонажа**.
4. Change the RP name, enter a date in `YYYY-MM-DD` format (or leave it blank), and add a short biography.
5. Save and confirm the success message appears.
6. Close and reopen the profile screen; verify the fields remain.
7. Leave and rejoin the same world to verify the details persist across sessions.
8. Check `logs/latest.log` for database errors if any save fails.

## Current limitations

Economy, professions, talents, factions, crime/jail, and medical status remain future server-owned domains. Character birth date is intended for the fictional roleplay character, not the real player.
