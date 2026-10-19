# HRPCore architecture notes

## First vertical slice

1. Common initializer and client initializer load independently.
2. Client key binding opens `HrpMainScreen`.
3. `/hrp` provides a basic server-side smoke test.
4. Vanilla health, armor, hotbar and other HUD elements remain visible.

## Planned next slice

- `common/network`: versioned request/response payloads.
- `common/model`: immutable DTOs for player profile and UI snapshots.
- `server/player`: profile lifecycle and session state.
- `server/persistence`: SQLite repository and schema migrations.
- `client/state`: client-side cache of server snapshots.

## Rules

- Never accept a balance, role, jail duration or permission result from the client as authoritative.
- Validate all incoming packet data on the server.
- Perform balance and credit changes atomically and record an audit entry.
- Store deadlines as absolute timestamps so server restarts do not reset timers.
- Keep Minecraft client classes out of common/server packages.
