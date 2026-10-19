# HRPCore architecture notes

## Implemented vertical slices

1. Common and client entrypoints load independently.
2. Client key binding opens `HrpMainScreen`; the vanilla HUD remains visible.
3. `/hrp` provides a basic server-side smoke test.
4. The client requests and displays a server-authoritative profile snapshot.
5. RP name, birth date and biography are validated by the server and persisted in SQLite.
6. SQLite access is serialized by a single-thread executor off the normal game tick.
7. The finance screen displays cash and bank balances, supports deposit, withdrawal and transfers, and shows recent transactions.
8. Deposit, withdrawal and player transfers use SQL transactions; transfers update both wallets and both transaction records atomically.

## Storage

- Database: `<world>/data/hrpcore/profiles.db`.
- `player_profiles`: profile identity, RP fields, first join and accumulated connected server ticks.
- `finance_accounts`: `uuid`, `cash_cents`, `bank_cents`, `updated_at`.
- `finance_transactions`: per-player operation journal with a shared transaction ID for both sides of a transfer.
- Money is stored as integer kopecks, not floating-point rubles.
- Schema version is tracked with SQLite `PRAGMA user_version`; migrations are sequential (v0 → v1 → v2 → v3).
- Version 2 → 3 creates finance tables and grants one starting wallet to existing players as a one-time migration. New accounts use the same starting amount once at creation.
- Profile snapshots and finance operations share the same single-thread executor/SQLite connection, so operations cannot race on the connection.

## Finance invariants

- The client sends an operation request, never a proposed balance.
- The server validates operation type, amount and recipient username.
- Amounts must be positive and no greater than 100,000,000.00 ₽.
- Cash and bank balances cannot go below zero (also enforced by SQL `CHECK` constraints).
- Deposit and withdrawal move equal amounts between cash and bank.
- A player transfer debits the sender, credits the recipient, and inserts both journal rows inside a single transaction. If any database statement fails, the transfer rolls back.
- Transfer target must be an existing Minecraft account in `player_profiles`; unknown names are rejected.
- The UI receives balances and the latest six history lines from the server, not from local calculations.

## General rules

- Never accept a balance, role, jail duration, or permission result from the client as authoritative.
- Validate all incoming packet data on the server and bound packet-derived strings/collections.
- Record an audit/journal entry for balance changes and perform related changes atomically.
- Store deadlines as absolute timestamps so server restarts do not reset timers.
- Keep client classes out of common/server packages.
- Keep disk/database work off the regular server tick.
- Every persistent schema change must be a sequential migration and must preserve existing rows.

## Planned next slices

1. Add administrator-only economy controls and a configurable starting balance.
2. Implement cash/bank transaction history filters and pagination.
3. Implement salaries and professions with auditable rewards.
4. Add talents, factions, crime/jail, and medical state as separate server-owned domains.
