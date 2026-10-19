# HRPCore architecture notes

## Implemented vertical slices

1. Common and client entrypoints load independently.
2. The client key binding opens `HrpMainScreen`; the vanilla HUD remains visible.
3. The client requests a server-produced player profile snapshot.
4. Character RP fields can be edited through a validated server request and persisted to SQLite.
5. Player profiles, cash wallets, bank balances, and transaction history persist in `<world>/data/hrpcore/profiles.db`.
6. SQLite operations are serialized through one dedicated executor, keeping disk I/O off the normal game tick.
7. Cash amounts are integer kopecks; there are no floating-point balance calculations.
8. Deposit, withdrawal, player transfers and admin adjustments update balances and insert transaction records transactionally.
9. Operator-only commands inspect balances and history or adjust cash/bank balances with a recorded administrator and reason.

## Database

- `player_profiles(uuid, player_name, rp_name, birth_date, biography, first_join, play_time_ticks)`.
- `finance_accounts(uuid, cash_cents, bank_cents, updated_at)`.
- `finance_transactions(id, transaction_id, player_uuid, type, amount_cents, cash_delta_cents, bank_delta_cents, description, created_at)`.
- Schema version is tracked by SQLite `PRAGMA user_version`; the current version is 3.
- Existing unversioned/profile databases are migrated in place; rows are not dropped or recreated.
- Transactions use SQLite constraints and explicit rollback on failure. Transfer debits, credits and both ledger rows commit or roll back together.
- Admin transaction descriptions include actor and reason. Types distinguish `ADMIN_GIVE`, `ADMIN_TAKE`, `ADMIN_GIVE_BANK`, and `ADMIN_TAKE_BANK`.
- Target usernames are resolved from `player_profiles`, so operators can administer offline players who joined previously.

## Server authority and concurrency

- Never accept a balance, target UUID, role, jail duration, or permission result from a client as authoritative.
- Client finance payloads contain only operation code, amount in kopecks and target username; the server derives the player's UUID from the connection.
- Admin commands require vanilla permission level 2 or higher.
- User-entered admin amounts are parsed with `BigDecimal`, checked for range and exact precision, then converted to integer kopecks.
- All repository calls use the single profile storage executor because the SQLite connection is intentionally not used concurrently.
- Results are marshalled back to the Minecraft server thread before command feedback or packets are sent.
- Admin adjustments are audited in the same SQLite transaction as the affected balance change.
- Absolute timestamps are stored for real-world deadlines; this matters when future jail, loans, or interest timers are added.

## Planned next slices

1. Add salary/payday configuration and a permissions-based pay multiplier.
2. Implement professional roles and their permissions/progression.
3. Add faction membership and government/police/medic roles.
4. Add crime, jail and medical status as separate server-owned domains.
5. Add automated tests against a temporary SQLite database and a dedicated-server smoke test.
