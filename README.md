# HRPCore — current implementation

HRPCore is an early Fabric mod for Minecraft Java Edition 1.21.10. This module uses Fabric APIs and SQLite; Bukkit, Paper, Vault and PlaceholderAPI are not used here.

## Implemented systems

- `/hrp` server smoke-test command and **O**-key roleplay interface while preserving the vanilla HUD.
- Server-authoritative profile snapshot and editable character fields: RP name, optional birth date, and short biography.
- SQLite profile persistence with sequential schema migrations (`PRAGMA user_version`), preserving existing profile data.
- Player finances: cash wallet, bank balance, deposit, withdrawal, transfer to an existing account, and recent transaction history.
- Economy calculations use integer kopecks rather than floating-point balances.
- SQLite operations are serialized through a dedicated executor; balance mutations and their transaction-log entries commit atomically.
- Operator-only economy commands to inspect balances/history and give or take cash/bank funds with a recorded reason.
- Server-side periodic salaries based on each player's persisted role, credited to the existing cash wallet and recorded in the transaction history.
- Each salary payout, its history entry, and its durable duplicate-prevention marker commit atomically in SQLite.

## Requirements

- JDK 21
- Minecraft Java Edition 1.21.10
- Matching Fabric Loader and Fabric API versions from `gradle.properties`

In IntelliJ IDEA, set the Gradle JVM to JDK 21. Build on Windows with `.\gradlew.bat build` and run the development client with `.\gradlew.bat runClient`. The distributable mod JAR is written to `build/libs/`.

## Data storage

The database is located at `<world>/data/hrpcore/profiles.db`. Back up the world folder to back up profiles, wallets, salary payout markers, and transaction history. Do not edit the database while the server is running. Every change to the persisted schema must be added as a sequential migration.

## Role salaries

On first server start, HRPCore creates `hrpcore-salary.properties` in Fabric's config directory. For the Gradle development client this is normally `run/config/hrpcore-salary.properties`; on a dedicated server it is `config/hrpcore-salary.properties` in the server directory. Edit it while the server is stopped; changes apply after a restart.

```properties
enabled=true
interval-minutes=60
salary.CITIZEN=500.00
salary.POLICE=1500.00
salary.MEDIC=1250.00
salary.GOVERNMENT=1000.00
salary.BANKER=1000.00
```

Amounts are in rubles and support up to two decimal places. Set a role's salary to `0.00` to disable payment for that role. The allowed interval is 1–10,080 minutes. Salaries are credited to the cash wallet, not the bank balance. Players must be online at the interval boundary to receive that period's salary; missed periods do not accumulate while offline or while the server is stopped. Payout periods are aligned to the system clock, and the database prevents duplicate payments for the same player, interval, and period.

## Economy admin commands

These commands require permission level 2 or higher (server operator):

- `/hrp admin balance <player>` — show cash and bank balances.
- `/hrp admin history <player>` — show the player's latest recorded operations.
- `/hrp admin give <player> <amount> [reason]` — add cash to a player's wallet.
- `/hrp admin take <player> <amount> [reason]` — remove cash if the player has enough.
- `/hrp admin givebank <player> <amount> [reason]` — add funds to the bank account.
- `/hrp admin takebank <player> <amount> [reason]` — remove bank funds if sufficient.

Amounts use rubles with optional two decimal places; either comma or dot is accepted (`250`, `250,50`, `250.50`). Maximum per operation is 100,000,000.00 ₽. Target names must belong to a player who has joined the world/server at least once. If no reason is supplied, a default reason is recorded. Administrator name, reason, time, amount and balance delta are written into `finance_transactions` in the same transaction as the balance update.

## Manual smoke test

1. Run `runClient`, join the same test world, and press **O**.
2. Open the profile, edit RP fields, save, leave, and reconnect. Confirm the fields persist.
3. Open **ФИНАНСЫ**. Check starting cash, deposit, withdrawal, transfer, and history persistence.
4. With an operator source, run `/hrp admin balance <player>`, `/hrp admin give <player> 25,50 test`, and `/hrp admin history <player>`.
5. Test that `/hrp admin take <player> 999999999` fails if the player does not have enough cash and never creates a negative balance.
6. For a short salary test, stop the server, edit the config file in the active game/server config directory, set `interval-minutes=1` and `salary.CITIZEN=1.25`, restart, and stay online until the next interval boundary. Confirm the wallet increases by exactly 1.25 ₽ and the transaction history contains a positive `SALARY` entry.
7. Restart the server during the same interval and confirm no duplicate salary is paid for that interval. The next interval should pay once again if you remain online.
8. Confirm `logs/latest.log` contains no storage errors.

## Current limitations

This is an incremental alpha. Profession/talent/faction systems, crime/jail, medical state, salary progression/payroll rules beyond the initial periodic role salary, and bank interest are not yet implemented. Admin economy actions currently adjust the selected player's cash or bank balance and are kept in the player's transaction history; a separate global moderation/audit dashboard is not yet included.
