package ru.hrp.core;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** SQLite persistence for player profiles and finances. All methods run on the profile storage executor. */
public final class HrpProfileStorage implements AutoCloseable {
    private static final int CURRENT_SCHEMA_VERSION = 4;
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
            .withZone(ZoneId.systemDefault());

    private static final String CREATE_PLAYER_PROFILES_SQL = """
            CREATE TABLE IF NOT EXISTS player_profiles (
                uuid TEXT PRIMARY KEY NOT NULL,
                player_name TEXT NOT NULL,
                first_join INTEGER NOT NULL CHECK (first_join >= 0),
                play_time_ticks INTEGER NOT NULL DEFAULT 0 CHECK (play_time_ticks >= 0)
            )
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO player_profiles
                (uuid, player_name, rp_name, birth_date, biography, first_join, play_time_ticks)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                rp_name = excluded.rp_name,
                birth_date = excluded.birth_date,
                biography = excluded.biography,
                first_join = MIN(player_profiles.first_join, excluded.first_join),
                play_time_ticks = MAX(player_profiles.play_time_ticks, excluded.play_time_ticks)
            """;

    private static final String CREATE_FINANCE_ACCOUNTS_SQL = """
            CREATE TABLE IF NOT EXISTS finance_accounts (
                uuid TEXT PRIMARY KEY NOT NULL,
                cash_cents INTEGER NOT NULL DEFAULT 0 CHECK (cash_cents >= 0),
                bank_cents INTEGER NOT NULL DEFAULT 0 CHECK (bank_cents >= 0),
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (uuid) REFERENCES player_profiles(uuid) ON DELETE CASCADE
            )
            """;

    private static final String CREATE_FINANCE_TRANSACTIONS_SQL = """
            CREATE TABLE IF NOT EXISTS finance_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                type TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
                cash_delta_cents INTEGER NOT NULL,
                bank_delta_cents INTEGER NOT NULL,
                description TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY (player_uuid) REFERENCES finance_accounts(uuid) ON DELETE CASCADE
            )
            """;

    private static final String CREATE_PLAYER_ROLES_SQL = """
            CREATE TABLE IF NOT EXISTS player_roles (
                uuid TEXT PRIMARY KEY NOT NULL,
                role TEXT NOT NULL,
                assigned_by TEXT NOT NULL,
                assigned_at INTEGER NOT NULL,
                reason TEXT NOT NULL,
                FOREIGN KEY (uuid) REFERENCES player_profiles(uuid) ON DELETE CASCADE
            )
            """;

    private static final String CREATE_ROLE_HISTORY_SQL = """
            CREATE TABLE IF NOT EXISTS role_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                previous_role TEXT NOT NULL,
                new_role TEXT NOT NULL,
                actor TEXT NOT NULL,
                reason TEXT NOT NULL,
                assigned_at INTEGER NOT NULL,
                FOREIGN KEY (player_uuid) REFERENCES player_profiles(uuid) ON DELETE CASCADE
            )
            """;

    private final Connection connection;

    public record StoredProfile(
            UUID uuid,
            String name,
            String rpName,
            String birthDate,
            String biography,
            long firstJoin,
            long playTimeTicks
    ) {
        public StoredProfile {
            if (uuid == null) throw new IllegalArgumentException("uuid must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (rpName == null || rpName.isBlank()) throw new IllegalArgumentException("rpName must not be blank");
            if (birthDate == null) throw new IllegalArgumentException("birthDate must not be null");
            if (biography == null) throw new IllegalArgumentException("biography must not be null");
            if (firstJoin < 0) throw new IllegalArgumentException("firstJoin must not be negative");
            if (playTimeTicks < 0) throw new IllegalArgumentException("playTimeTicks must not be negative");
        }
    }

    public record FinanceAccount(UUID uuid, long cashCents, long bankCents) {
        public FinanceAccount {
            if (uuid == null) throw new IllegalArgumentException("uuid must not be null");
            if (cashCents < 0 || bankCents < 0) throw new IllegalArgumentException("balances must not be negative");
        }
    }

    /** A business-level result. SQL failures still throw SQLException. */
    public record FinanceOperation(boolean success, String message, UUID counterpartyUuid) {}

    public record RoleOperationResult(boolean success, String message, HrpRole previousRole, HrpRole newRole) {}

    public record KnownPlayer(UUID uuid, String name) {
        public KnownPlayer {
            if (uuid == null) throw new IllegalArgumentException("uuid must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        }
    }

    public HrpProfileStorage(Path databasePath) throws IOException, SQLException {
        Path absolutePath = databasePath.toAbsolutePath();
        Path parent = absolutePath.getParent();
        if (parent != null) Files.createDirectories(parent);

        connection = DriverManager.getConnection("jdbc:sqlite:" + absolutePath);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA foreign_keys = ON");
            }
            migrateSchema();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.close();
            } catch (SQLException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /** Creates a profile on first join or returns the existing persisted row. */
    public StoredProfile loadOrCreate(UUID uuid, String playerName, long nowMillis) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO player_profiles
                    (uuid, player_name, rp_name, birth_date, biography, first_join, play_time_ticks)
                VALUES (?, ?, ?, '', '', ?, 0)
                ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name
                """)) {
            insert.setString(1, uuid.toString());
            insert.setString(2, playerName);
            insert.setString(3, playerName);
            insert.setLong(4, nowMillis);
            insert.executeUpdate();
        }

        try (PreparedStatement select = connection.prepareStatement("""
                SELECT player_name, rp_name, birth_date, biography, first_join, play_time_ticks
                FROM player_profiles WHERE uuid = ?
                """)) {
            select.setString(1, uuid.toString());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) throw new SQLException("Profile row missing after upsert for UUID " + uuid);
                String storedName = result.getString("player_name");
                String rpName = result.getString("rp_name");
                if (rpName == null || rpName.isBlank()) rpName = storedName;
                return new StoredProfile(uuid, storedName, rpName,
                        result.getString("birth_date"), result.getString("biography"),
                        result.getLong("first_join"), result.getLong("play_time_ticks"));
            }
        }
    }

    /** Updates only character-editable RP fields; account UUID and session counters remain server-owned. */
    public void updateCharacterProfile(UUID uuid, String rpName, String birthDate, String biography)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE player_profiles SET rp_name = ?, birth_date = ?, biography = ? WHERE uuid = ?
                """)) {
            update.setString(1, rpName);
            update.setString(2, birthDate);
            update.setString(3, biography);
            update.setString(4, uuid.toString());
            int changed = update.executeUpdate();
            if (changed != 1) throw new SQLException("Expected to update one profile row for UUID " + uuid);
        }
    }

    public void save(StoredProfile profile) throws SQLException {
        saveAll(List.of(profile));
    }

    /** Saves a consistent profile snapshot in one SQLite transaction. */
    public void saveAll(Collection<StoredProfile> profiles) throws SQLException {
        if (profiles.isEmpty()) return;
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement upsert = connection.prepareStatement(UPSERT_SQL)) {
            for (StoredProfile profile : profiles) {
                upsert.setString(1, profile.uuid().toString());
                upsert.setString(2, profile.name());
                upsert.setString(3, profile.rpName());
                upsert.setString(4, profile.birthDate());
                upsert.setString(5, profile.biography());
                upsert.setLong(6, profile.firstJoin());
                upsert.setLong(7, profile.playTimeTicks());
                upsert.addBatch();
            }
            upsert.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Finds a player who has joined this server before. Matching ignores username case. */
    public KnownPlayer findKnownPlayerByName(String playerName) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT uuid, player_name FROM player_profiles
                WHERE player_name COLLATE NOCASE = ? LIMIT 1
                """)) {
            select.setString(1, playerName);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) return null;
                return new KnownPlayer(UUID.fromString(result.getString("uuid")), result.getString("player_name"));
            }
        }
    }

    /**
     * Applies an audited administrator adjustment to a player's cash or bank balance.
     * Account mutation and history record commit or roll back together.
     */
    public FinanceOperation adminAdjustBalance(UUID targetUuid, long amountCents, boolean give,
                                               boolean bankAccount, String adminName, String reason,
                                               long nowMillis) throws SQLException {
        if (amountCents <= 0) return new FinanceOperation(false, "Сумма должна быть больше нуля.", null);
        ensureFinanceAccount(targetUuid, 0, nowMillis);

        String targetName = findPlayerName(targetUuid);
        String column = bankAccount ? "bank_cents" : "cash_cents";
        String type = bankAccount
                ? (give ? "ADMIN_GIVE_BANK" : "ADMIN_TAKE_BANK")
                : (give ? "ADMIN_GIVE" : "ADMIN_TAKE");
        long delta = give ? amountCents : -amountCents;
        String safeAdmin = (adminName == null || adminName.isBlank()) ? "CONSOLE" : adminName;
        String safeReason = (reason == null || reason.isBlank()) ? "без указания причины" : reason;
        String description = "Администратор " + safeAdmin + ": " + safeReason;

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            String sql = give
                    ? "UPDATE finance_accounts SET " + column + " = " + column
                        + " + ?, updated_at = ? WHERE uuid = ? AND " + column + " <= ?"
                    : "UPDATE finance_accounts SET " + column + " = " + column
                        + " - ?, updated_at = ? WHERE uuid = ? AND " + column + " >= ?";
            try (PreparedStatement update = connection.prepareStatement(sql)) {
                update.setLong(1, amountCents);
                update.setLong(2, nowMillis);
                update.setString(3, targetUuid.toString());
                update.setLong(4, give ? Long.MAX_VALUE - amountCents : amountCents);
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return new FinanceOperation(false, give
                            ? "Невозможно выдать сумму: баланс достиг предельного значения."
                            : (bankAccount ? "Недостаточно денег на банковском счёте игрока."
                                           : "Недостаточно наличных у игрока."), null);
                }
            }

            insertTransaction(UUID.randomUUID().toString(), targetUuid, type, amountCents,
                    bankAccount ? 0 : delta, bankAccount ? delta : 0, description, nowMillis);
            connection.commit();
            String amountText = BigDecimal.valueOf(amountCents, 2).toPlainString().replace('.', ',');
            String message;
            if (bankAccount) {
                message = give
                        ? "На банковский счёт игрока " + targetName + " зачислено " + amountText + " ₽."
                        : "С банковского счёта игрока " + targetName + " списано " + amountText + " ₽.";
            } else {
                message = give
                        ? "Игроку " + targetName + " выдано наличными " + amountText + " ₽."
                        : "С игрока " + targetName + " списано наличными " + amountText + " ₽.";
            }
            return new FinanceOperation(true, message, targetUuid);
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Ensures a wallet exists, crediting the configured starting cash exactly once. */
    public FinanceAccount loadFinanceAccount(UUID uuid, long startingCashCents, long nowMillis) throws SQLException {
        ensureFinanceAccount(uuid, startingCashCents, nowMillis);
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT cash_cents, bank_cents FROM finance_accounts WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) throw new SQLException("Finance account missing for UUID " + uuid);
                return new FinanceAccount(uuid, result.getLong("cash_cents"), result.getLong("bank_cents"));
            }
        }
    }

    private void ensureFinanceAccount(UUID uuid, long startingCashCents, long nowMillis) throws SQLException {
        if (startingCashCents < 0) throw new IllegalArgumentException("startingCashCents must not be negative");
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO finance_accounts (uuid, cash_cents, bank_cents, updated_at)
                VALUES (?, ?, 0, ?) ON CONFLICT(uuid) DO NOTHING
                """)) {
            insert.setString(1, uuid.toString());
            insert.setLong(2, startingCashCents);
            insert.setLong(3, nowMillis);
            insert.executeUpdate();
        }
    }

    /** Moves cash into the bank account in an atomic transaction. */
    public FinanceOperation deposit(UUID uuid, long amountCents, long nowMillis) throws SQLException {
        if (amountCents <= 0) return new FinanceOperation(false, "Сумма должна быть больше нуля.", null);
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE finance_accounts
                SET cash_cents = cash_cents - ?, bank_cents = bank_cents + ?, updated_at = ?
                WHERE uuid = ? AND cash_cents >= ?
                """)) {
            update.setLong(1, amountCents);
            update.setLong(2, amountCents);
            update.setLong(3, nowMillis);
            update.setString(4, uuid.toString());
            update.setLong(5, amountCents);
            if (update.executeUpdate() != 1) {
                connection.rollback();
                return new FinanceOperation(false, "Недостаточно наличных для пополнения счёта.", null);
            }
            insertTransaction(UUID.randomUUID().toString(), uuid, "DEPOSIT", amountCents,
                    -amountCents, amountCents, "Внесение в банк", nowMillis);
            connection.commit();
            return new FinanceOperation(true, "Средства внесены на банковский счёт.", null);
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Withdraws bank funds into cash in an atomic transaction. */
    public FinanceOperation withdraw(UUID uuid, long amountCents, long nowMillis) throws SQLException {
        if (amountCents <= 0) return new FinanceOperation(false, "Сумма должна быть больше нуля.", null);
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE finance_accounts
                SET cash_cents = cash_cents + ?, bank_cents = bank_cents - ?, updated_at = ?
                WHERE uuid = ? AND bank_cents >= ?
                """)) {
            update.setLong(1, amountCents);
            update.setLong(2, amountCents);
            update.setLong(3, nowMillis);
            update.setString(4, uuid.toString());
            update.setLong(5, amountCents);
            if (update.executeUpdate() != 1) {
                connection.rollback();
                return new FinanceOperation(false, "Недостаточно средств на банковском счёте.", null);
            }
            insertTransaction(UUID.randomUUID().toString(), uuid, "WITHDRAW", amountCents,
                    amountCents, -amountCents, "Снятие наличных", nowMillis);
            connection.commit();
            return new FinanceOperation(true, "Средства сняты с банковского счёта.", null);
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Transfers cash to an existing Minecraft account by username, including offline players. */
    public FinanceOperation transferCash(UUID senderUuid, String targetName, long amountCents, long nowMillis)
            throws SQLException {
        if (amountCents <= 0) return new FinanceOperation(false, "Сумма должна быть больше нуля.", null);

        UUID recipientUuid;
        String canonicalTargetName;
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT uuid, player_name FROM player_profiles
                WHERE player_name COLLATE NOCASE = ? LIMIT 1
                """)) {
            select.setString(1, targetName);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return new FinanceOperation(false, "Игрок не найден. Он должен хотя бы один раз зайти на сервер.", null);
                }
                recipientUuid = UUID.fromString(result.getString("uuid"));
                canonicalTargetName = result.getString("player_name");
            }
        }
        if (senderUuid.equals(recipientUuid)) {
            return new FinanceOperation(false, "Нельзя переводить деньги самому себе.", null);
        }

        // Legacy accounts are backfilled with zero only; this cannot mint starter money during a transfer.
        ensureFinanceAccount(recipientUuid, 0, nowMillis);
        String senderName = findPlayerName(senderUuid);
        String transactionId = UUID.randomUUID().toString();
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement debit = connection.prepareStatement("""
                    UPDATE finance_accounts SET cash_cents = cash_cents - ?, updated_at = ?
                    WHERE uuid = ? AND cash_cents >= ?
                    """)) {
                debit.setLong(1, amountCents);
                debit.setLong(2, nowMillis);
                debit.setString(3, senderUuid.toString());
                debit.setLong(4, amountCents);
                if (debit.executeUpdate() != 1) {
                    connection.rollback();
                    return new FinanceOperation(false, "Недостаточно наличных для перевода.", null);
                }
            }
            try (PreparedStatement credit = connection.prepareStatement("""
                    UPDATE finance_accounts SET cash_cents = cash_cents + ?, updated_at = ? WHERE uuid = ?
                    """)) {
                credit.setLong(1, amountCents);
                credit.setLong(2, nowMillis);
                credit.setString(3, recipientUuid.toString());
                if (credit.executeUpdate() != 1) throw new SQLException("Recipient finance account not found");
            }

            insertTransaction(transactionId, senderUuid, "TRANSFER_OUT", amountCents,
                    -amountCents, 0, "Перевод игроку " + canonicalTargetName, nowMillis);
            insertTransaction(transactionId, recipientUuid, "TRANSFER_IN", amountCents,
                    amountCents, 0, "Перевод от " + senderName, nowMillis);
            connection.commit();
            return new FinanceOperation(true, "Перевод игроку " + canonicalTargetName + " выполнен.", recipientUuid);
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Returns recent, already formatted history lines for the finance screen. */
    public List<String> loadFinanceHistory(UUID uuid, int limit) throws SQLException {
        int safeLimit = Math.max(1, Math.min(limit, 8));
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT created_at, type, amount_cents, description
                FROM finance_transactions WHERE player_uuid = ? ORDER BY id DESC LIMIT ?
                """)) {
            select.setString(1, uuid.toString());
            select.setInt(2, safeLimit);
            try (ResultSet result = select.executeQuery()) {
                java.util.ArrayList<String> history = new java.util.ArrayList<>();
                while (result.next()) {
                    String type = result.getString("type");
                    String sign = switch (type) {
                        case "WITHDRAW", "TRANSFER_IN", "ADMIN_GIVE", "ADMIN_GIVE_BANK" -> "+";
                        default -> "−";
                    };
                    long amount = result.getLong("amount_cents");
                    String amountText = BigDecimal.valueOf(amount, 2).toPlainString().replace('.', ',');
                    String time = HISTORY_TIME.format(Instant.ofEpochMilli(result.getLong("created_at")));
                    history.add(time + " · " + result.getString("description") + " · " + sign + amountText + " ₽");
                }
                return List.copyOf(history);
            }
        }
    }

    private String findPlayerName(UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT player_name FROM player_profiles WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet result = select.executeQuery()) {
                return result.next() ? result.getString(1) : "игрок";
            }
        }
    }

    private void insertTransaction(String transactionId, UUID playerUuid, String type,
                                   long amountCents, long cashDeltaCents, long bankDeltaCents,
                                   String description, long createdAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO finance_transactions
                    (transaction_id, player_uuid, type, amount_cents, cash_delta_cents,
                     bank_delta_cents, description, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, transactionId);
            insert.setString(2, playerUuid.toString());
            insert.setString(3, type);
            insert.setLong(4, amountCents);
            insert.setLong(5, cashDeltaCents);
            insert.setLong(6, bankDeltaCents);
            insert.setString(7, description);
            insert.setLong(8, createdAt);
            insert.executeUpdate();
        }
    }

    /** Ensures player has a role entry in player_roles; defaults to CITIZEN if missing. */
    public HrpRole loadOrCreateRole(UUID uuid, long nowMillis) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO player_roles (uuid, role, assigned_by, assigned_at, reason)
                VALUES (?, ?, 'SYSTEM', ?, 'Автоматическое назначение при первом входе')
                ON CONFLICT(uuid) DO NOTHING
                """)) {
            insert.setString(1, uuid.toString());
            insert.setString(2, HrpRole.CITIZEN.getId());
            insert.setLong(3, nowMillis);
            insert.executeUpdate();
        }

        return getPlayerRole(uuid);
    }

    /** Gets current assigned role for player UUID. Returns CITIZEN if no record exists. */
    public HrpRole getPlayerRole(UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT role FROM player_roles WHERE uuid = ?
                """)) {
            select.setString(1, uuid.toString());
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    HrpRole role = HrpRole.parse(result.getString("role"));
                    if (role != null) return role;
                }
            }
        }
        return HrpRole.CITIZEN;
    }

    /**
     * Assigns a role to a player and records audit history in a single SQL transaction.
     * Rejects operation if the player already has the same role.
     */
    public RoleOperationResult setPlayerRole(UUID targetUuid, HrpRole newRole, String actorName,
                                             String reason, long nowMillis) throws SQLException {
        if (newRole == null) {
            return new RoleOperationResult(false, "Некорректный идентификатор роли.", null, null);
        }

        String targetName = findPlayerName(targetUuid);
        HrpRole currentRole = loadOrCreateRole(targetUuid, nowMillis);

        if (currentRole == newRole) {
            return new RoleOperationResult(false,
                    "Игрок " + targetName + " уже имеет роль " + newRole.getFormatted() + ".",
                    currentRole, currentRole);
        }

        String safeActor = (actorName == null || actorName.isBlank()) ? "CONSOLE" : actorName;
        String safeReason = (reason == null || reason.isBlank()) ? "без указания причины" : reason;

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE player_roles
                    SET role = ?, assigned_by = ?, assigned_at = ?, reason = ?
                    WHERE uuid = ?
                    """)) {
                update.setString(1, newRole.getId());
                update.setString(2, safeActor);
                update.setLong(3, nowMillis);
                update.setString(4, safeReason);
                update.setString(5, targetUuid.toString());
                int rows = update.executeUpdate();
                if (rows != 1) {
                    connection.rollback();
                    return new RoleOperationResult(false, "Не удалось обновить роль игрока.", currentRole, newRole);
                }
            }

            try (PreparedStatement insertHistory = connection.prepareStatement("""
                    INSERT INTO role_history
                        (player_uuid, previous_role, new_role, actor, reason, assigned_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insertHistory.setString(1, targetUuid.toString());
                insertHistory.setString(2, currentRole.getId());
                insertHistory.setString(3, newRole.getId());
                insertHistory.setString(4, safeActor);
                insertHistory.setString(5, safeReason);
                insertHistory.setLong(6, nowMillis);
                insertHistory.executeUpdate();
            }

            connection.commit();
            return new RoleOperationResult(true,
                    "Игроку " + targetName + " назначена роль: " + newRole.getFormatted() + ".",
                    currentRole, newRole);
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void migrateSchema() throws SQLException {
        int version = readSchemaVersion();
        if (version > CURRENT_SCHEMA_VERSION) {
            throw new SQLException("Database schema version " + version
                    + " is newer than this HRPCore build supports (" + CURRENT_SCHEMA_VERSION + ")");
        }
        while (version < CURRENT_SCHEMA_VERSION) {
            switch (version) {
                case 0 -> migrateV0ToV1();
                case 1 -> migrateV1ToV2();
                case 2 -> migrateV2ToV3();
                case 3 -> migrateV3ToV4();
                default -> throw new SQLException("No migration available from schema version " + version);
            }
            version = readSchemaVersion();
        }
    }

    private int readSchemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) throw new SQLException("SQLite did not return PRAGMA user_version");
            return result.getInt(1);
        }
    }

    /** Version 0 is the original unversioned database. Preserve its rows in place. */
    private void migrateV0ToV1() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_PLAYER_PROFILES_SQL);
            statement.execute("PRAGMA user_version = 1");
            connection.commit();
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Adds player_roles and role_history tables; backfills default CITIZEN roles for existing player profiles. */
    private void migrateV3ToV4() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        long nowMillis = System.currentTimeMillis();
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_PLAYER_ROLES_SQL);
            statement.execute(CREATE_ROLE_HISTORY_SQL);
            try (PreparedStatement seed = connection.prepareStatement("""
                    INSERT OR IGNORE INTO player_roles (uuid, role, assigned_by, assigned_at, reason)
                    SELECT uuid, 'CITIZEN', 'SYSTEM', ?, 'Роль назначена по умолчанию при миграции схемы v4'
                    FROM player_profiles
                    """)) {
                seed.setLong(1, nowMillis);
                seed.executeUpdate();
            }
            statement.execute("CREATE INDEX IF NOT EXISTS idx_role_history_player_uuid "
                    + "ON role_history(player_uuid, id DESC)");
            statement.execute("PRAGMA user_version = 4");
            connection.commit();
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Adds character RP fields. Existing profiles keep first-join and play-time values. */
    private void migrateV1ToV2() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE player_profiles ADD COLUMN rp_name TEXT NOT NULL DEFAULT ''");
            statement.execute("ALTER TABLE player_profiles ADD COLUMN birth_date TEXT NOT NULL DEFAULT ''");
            statement.execute("ALTER TABLE player_profiles ADD COLUMN biography TEXT NOT NULL DEFAULT ''");
            statement.executeUpdate("UPDATE player_profiles SET rp_name = player_name WHERE TRIM(rp_name) = ''");
            statement.execute("PRAGMA user_version = 2");
            connection.commit();
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    /** Adds finance tables and grants the alpha starting cash once to profiles already on the server. */
    private void migrateV2ToV3() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_FINANCE_ACCOUNTS_SQL);
            statement.execute(CREATE_FINANCE_TRANSACTIONS_SQL);
            try (PreparedStatement seed = connection.prepareStatement("""
                    INSERT OR IGNORE INTO finance_accounts (uuid, cash_cents, bank_cents, updated_at)
                    SELECT uuid, 100000, 0, ? FROM player_profiles
                    """)) {
                seed.setLong(1, System.currentTimeMillis());
                seed.executeUpdate();
            }
            statement.execute("CREATE INDEX IF NOT EXISTS idx_finance_transactions_player_id "
                    + "ON finance_transactions(player_uuid, id DESC)");
            statement.execute("PRAGMA user_version = 3");
            connection.commit();
        } catch (SQLException exception) {
            rollbackAndSuppress(exception);
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void rollbackAndSuppress(SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
