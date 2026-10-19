# HRPCore — profile and finance slice

HRPCore is an early Fabric mod for Minecraft Java Edition 1.21.10. Bukkit/Paper/Vault/PlaceholderAPI APIs are not used in this Fabric module.

## Implemented

- `/hrp` server smoke-test command.
- Press **O** to open the HRPCore interface while preserving the vanilla HUD.
- Server-authoritative profile snapshot with account name, RP name, birthday, biography, first join and session play time.
- Edit RP-facing profile fields with server-side validation and SQLite persistence.
- SQLite storage with sequential schema migrations through `PRAGMA user_version`.
- Finance screen with cash wallet, bank account, cash deposit, cash withdrawal and player-to-player transfers by Minecraft username.
- Finance operations use a single SQLite transaction and write a per-player transaction journal; transfers debit and credit both players atomically.
- SQLite work is serialized on a dedicated single-thread executor rather than the regular game tick.
- SQLite JDBC is bundled as a nested library in the mod JAR.

## Requirements

- JDK 21
- Minecraft Java Edition 1.21.10
- Matching Fabric Loader and Fabric API versions from `gradle.properties`

In IntelliJ IDEA, set the Gradle JVM to JDK 21. Build with `./gradlew build` (Windows: `gradlew.bat build`). The mod JAR is written to `build/libs/`.

## Profile and finance database

The database is stored at `<world>/data/hrpcore/profiles.db`. Back up the world folder to back up player data. Do not edit the database while the server is running.

Schema version 3 contains:

- `player_profiles`: Minecraft account and RP character profile fields.
- `finance_accounts`: cash and bank balances stored as integer kopecks (100 kopecks = 1 ruble).
- `finance_transactions`: immutable operation journal rows. A player transfer writes both the sender and recipient journal entries in the same SQL transaction.

When schema version 2 is upgraded to version 3, existing profiles receive a one-time starting cash balance of **1,000.00 ₽**. New profiles receive the same starting balance when their account is first created. Later joins do not grant money again. This is an alpha default, currently set by `STARTING_CASH_CENTS` in `HrpProfileService`.

## Finance screen smoke test

1. Run `runClient` and join a world.
2. Press **O**, then open **ФИНАНСЫ**.
3. Verify the initial cash balance shows `1 000,00 ₽` in a world upgraded from schema version 2, or for a newly created profile.
4. Deposit a small amount, such as `100,00`, and verify cash decreases while the bank balance increases.
5. Withdraw the same amount and verify the balances return to their previous values.
6. If a second player profile exists in the same world, transfer a small amount to that Minecraft username. Transfers to unknown usernames are rejected; the recipient must have joined the server at least once.
7. Verify the operation appears in history and the result persists after reopening the world.

## Current limitations

- There is no interest, loan, overdraft, economy command for administrators or configurable starting balance yet.
- Player-to-player transfers use cash wallet funds and Minecraft account usernames, including known offline players.
- Finance is not integrated with shops, salaries or jobs yet.
- Professions, talents, factions, crime/jail and medical status are still planned domains.
