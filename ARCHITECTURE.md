# HRPCore architecture notes

## Implemented vertical slice

1. Common and client entrypoints load independently.
2. Client key binding opens `HrpMainScreen`; the vanilla HUD remains visible.
3. `/hrp` provides a server-side smoke-test command.
4. Profile requests and snapshots travel through registered Fabric payloads.
5. Active profiles are cached in memory and persisted to SQLite.
6. RP name, fictional character birth date, and short biography can be edited from the client.
7. Client requests do not include a target UUID. The server always derives the target player from the network context.
8. SQLite work is serialized by a dedicated executor so database I/O does not block the regular server tick.

## Profile storage

- Repository: `HrpProfileStorage`.
- Service: `HrpProfileService`.
- Database: `<world>/data/hrpcore/profiles.db`.
- Current table: `player_profiles(uuid, player_name, first_join, play_time_ticks, rp_name, birth_date, biography)`.
- `first_join` is Unix epoch milliseconds.
- `play_time_ticks` counts server ticks while connected, not world day/night time.
- Snapshots are saved roughly once per minute, on disconnect, and during orderly shutdown.
- UPSERTs preserve the earliest first-join value and prevent the persisted play-time counter from moving backwards.

## Schema migrations

- Version 0 -> 1: create the original profile table if absent; preserve a previously existing unversioned table.
- Version 1 -> 2: add `rp_name`, `birth_date`, and `biography`; seed the RP name from the existing Minecraft player name.
- An unsupported newer schema version stops storage initialization with a clear error rather than silently changing it.
- Future persistent changes must use sequential migrations. Do not delete or recreate the production table to change its shape.

## Payloads

- `RequestProfile` (C2S): request the sender's profile.
- `ProfileResponse` (S2C): server snapshot of account and RP profile data.
- `SaveCharacterProfile` (C2S): proposed RP name, birth date, and biography only.
- `ProfileUpdateResult` (S2C): save result and a player-readable message.

The server validates RP name length/characters, date format and future dates, and biography length/control characters. Validation remains server-side even though the GUI limits text input.

## Rules

- Never accept balances, roles, jail duration, or permission decisions as authoritative from the client.
- Validate every client request on the server and derive player identity from the authenticated network context.
- Perform finance changes atomically and record an audit entry.
- Store deadlines as absolute timestamps so restarts do not reset timers.
- Keep client-only classes out of common/server packages.
- Keep disk/database work off the regular server tick.
