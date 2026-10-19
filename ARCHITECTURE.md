# HRPCore architecture notes

## Implemented first vertical slice

1. Common and client entrypoints load independently.
2. Client key binding opens `HrpMainScreen`.
3. `/hrp` provides a basic server-side smoke test.
4. Vanilla health, armor, hotbar, and other HUD elements remain visible.
5. Client sends a profile request and renders only a server-provided snapshot.
6. Active player profiles are cached in memory and persisted to SQLite.
7. SQLite work is serialized by a dedicated executor so normal tick processing does not block on disk I/O.
8. The profile database uses schema versioning through SQLite `PRAGMA user_version`.

## Profile persistence

- Repository: `HrpProfileStorage`.
- Database: `<world>/data/hrpcore/profiles.db`.
- Table: `player_profiles(uuid, player_name, first_join, play_time_ticks)`.
- `first_join` is stored as Unix epoch milliseconds.
- `play_time_ticks` counts server ticks while the player is connected; it is not derived from the world's day/night clock.
- Snapshots are saved roughly once a minute, on disconnect, and during orderly server shutdown.
- SQLite upserts preserve the earliest first-join value and prevent the saved play-time counter from moving backwards.
- `PRAGMA user_version = 1` marks the initial versioned schema. Version 0 upgrades create the profile table only if it does not already exist, retaining data from the unversioned implementation.
- An unsupported newer schema version causes a clear storage initialization error instead of silently modifying a database created by newer code.

## Rules

- Never accept a balance, role, jail duration, or permission result from the client as authoritative.
- Validate incoming packet data on the server.
- Perform balance and credit changes atomically and record an audit entry.
- Store deadlines as absolute timestamps so server restarts do not reset timers.
- Keep Minecraft client classes out of common/server packages.
- Keep disk/database work off the regular server tick.
- Add every future persistent schema change as a sequential migration.

## Planned next slices

1. Introduce shared immutable DTOs for each UI snapshot.
2. Add player-facing profile fields and server-side validation.
3. Implement economy and transaction audit logging.
4. Add professions, talents, factions, crime/jail, and medical status as separate server-owned domains.
