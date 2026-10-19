# HRPCore architecture notes

## Implemented first vertical slice

1. Common initializer and client initializer load independently.
2. Client key binding opens `HrpMainScreen`.
3. `/hrp` provides a basic server-side smoke test.
4. Vanilla health, armor, hotbar and other HUD elements remain visible.
5. Client sends a profile request and only renders a server-provided snapshot.
6. Server profile data is cached in memory for active players and persisted to SQLite.
7. SQLite reads and writes are serialized by a dedicated executor so normal tick processing does not block on disk I/O.

## Profile persistence

- Repository: `HrpProfileStorage`.
- Database: `<world>/data/hrpcore/profiles.db`.
- Table: `player_profiles(uuid, player_name, first_join, play_time_ticks)`.
- `first_join` is stored as Unix epoch milliseconds.
- `play_time_ticks` counts server ticks while the player is connected; it is not derived from the world's day/night clock.
- Snapshots are saved roughly once a minute, on disconnect, and during orderly server shutdown.
- SQLite UPSERTs preserve the earliest first-join value and prevent the saved play-time counter from moving backwards.

## Rules

- Never accept a balance, role, jail duration or permission result from the client as authoritative.
- Validate incoming packet data on the server.
- Perform balance and credit changes atomically and record an audit entry.
- Store deadlines as absolute timestamps so server restarts do not reset timers.
- Keep Minecraft client classes out of common/server packages.
- Keep disk/database work off the regular server tick.

## Planned next slices

1. Add migration/version tracking for the persistent schema.
2. Introduce shared immutable DTOs for each UI snapshot.
3. Add player-facing profile fields and server-side validation.
4. Implement economy and transaction audit logging.
5. Add professions, talents, factions, crime/jail, and medical status as separate server-owned domains.
