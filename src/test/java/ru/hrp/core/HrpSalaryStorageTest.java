package ru.hrp.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hrp.core.HrpProfileStorage.FinanceAccount;
import ru.hrp.core.HrpProfileStorage.SalaryPaymentResult;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HrpSalaryStorageTest {
    @TempDir
    Path tempDir;

    private Path dbPath;
    private HrpProfileStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("profiles.db");
        storage = new HrpProfileStorage(dbPath);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) storage.close();
    }

    @Test
    void salaryCreditsWalletAndHistoryExactlyOncePerPeriod() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        storage.loadOrCreate(uuid, "SalaryTester", now);
        storage.loadFinanceAccount(uuid, 100_000L, now);

        SalaryPaymentResult first = storage.paySalary(uuid, HrpRole.CITIZEN,
                50_000L, 60, 123_456L, now);

        assertTrue(first.paid());
        assertFalse(first.duplicate());
        FinanceAccount afterFirstPayment = storage.loadFinanceAccount(uuid, 100_000L, now);
        assertEquals(150_000L, afterFirstPayment.cashCents());

        // Reopen the same database to simulate a server restart.
        storage.close();
        storage = new HrpProfileStorage(dbPath);

        SalaryPaymentResult duplicate = storage.paySalary(uuid, HrpRole.CITIZEN,
                50_000L, 60, 123_456L, now + 10_000L);

        assertFalse(duplicate.paid());
        assertTrue(duplicate.duplicate());
        assertEquals(150_000L, storage.loadFinanceAccount(uuid, 100_000L, now).cashCents());
        assertTrue(storage.loadFinanceHistory(uuid, 8).get(0).contains("Зарплата: Гражданин"));
        assertTrue(storage.loadFinanceHistory(uuid, 8).get(0).contains("+500,00 ₽"));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM salary_payouts WHERE player_uuid = '" + uuid + "'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void aNewPeriodCanBePaidAndIsRecordedSeparately() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        storage.loadOrCreate(uuid, "PeriodTester", now);
        storage.loadFinanceAccount(uuid, 0L, now);

        assertTrue(storage.paySalary(uuid, HrpRole.MEDIC, 125_000L, 60, 100L, now).paid());
        assertTrue(storage.paySalary(uuid, HrpRole.MEDIC, 125_000L, 60, 101L, now + 60_000L).paid());
        assertEquals(250_000L, storage.loadFinanceAccount(uuid, 0L, now).cashCents());
    }

    @Test
    void historyFailureRollsBackWalletAndDuplicateMarker() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        storage.loadOrCreate(uuid, "RollbackTester", now);
        storage.loadFinanceAccount(uuid, 100_000L, now);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TRIGGER reject_salary_history
                    BEFORE INSERT ON finance_transactions
                    WHEN NEW.type = 'SALARY'
                    BEGIN
                        SELECT RAISE(ABORT, 'test salary history failure');
                    END
                    """);
        }

        assertThrows(SQLException.class, () -> storage.paySalary(
                uuid, HrpRole.POLICE, 150_000L, 60, 222L, now));
        assertEquals(100_000L, storage.loadFinanceAccount(uuid, 100_000L, now).cashCents());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM salary_payouts WHERE player_uuid = '" + uuid + "'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void walletOverflowRejectsSalaryAndRollsBackPayoutMarker() throws Exception {
        UUID uuid = UUID.randomUUID();
        long now = System.currentTimeMillis();
        storage.loadOrCreate(uuid, "OverflowTester", now);
        storage.loadFinanceAccount(uuid, 0L, now);
        long nearMaximum = Long.MAX_VALUE - 100L;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             var update = conn.prepareStatement("UPDATE finance_accounts SET cash_cents = ? WHERE uuid = ?")) {
            update.setLong(1, nearMaximum);
            update.setString(2, uuid.toString());
            assertEquals(1, update.executeUpdate());
        }

        assertThrows(SQLException.class, () -> storage.paySalary(
                uuid, HrpRole.BANKER, 200L, 60, 999L, now));
        assertEquals(nearMaximum, storage.loadFinanceAccount(uuid, 0L, now).cashCents());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM salary_payouts WHERE player_uuid = '" + uuid + "'")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void freshDatabaseUsesSchemaVersionSixAndCreatesSalaryLedger() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet version = stmt.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(6, version.getInt(1));
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet table = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'salary_payouts'")) {
            assertTrue(table.next());
            assertEquals("salary_payouts", table.getString(1));
        }
    }

    @Test
    void migrationFromVersionFivePreservesWalletAndAddsSalaryLedger() throws Exception {
        Path v5DbPath = tempDir.resolve("v5_profiles.db");
        UUID uuid = UUID.randomUUID();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + v5DbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE player_profiles (uuid TEXT PRIMARY KEY NOT NULL, player_name TEXT NOT NULL, rp_name TEXT NOT NULL, birth_date TEXT NOT NULL, biography TEXT NOT NULL, first_join INTEGER NOT NULL, play_time_ticks INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE finance_accounts (uuid TEXT PRIMARY KEY NOT NULL, cash_cents INTEGER NOT NULL DEFAULT 0 CHECK (cash_cents >= 0), bank_cents INTEGER NOT NULL DEFAULT 0 CHECK (bank_cents >= 0), updated_at INTEGER NOT NULL, FOREIGN KEY (uuid) REFERENCES player_profiles(uuid) ON DELETE CASCADE)");
            stmt.execute("CREATE TABLE finance_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT NOT NULL, player_uuid TEXT NOT NULL, type TEXT NOT NULL, amount_cents INTEGER NOT NULL CHECK (amount_cents > 0), cash_delta_cents INTEGER NOT NULL, bank_delta_cents INTEGER NOT NULL, description TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY (player_uuid) REFERENCES finance_accounts(uuid) ON DELETE CASCADE)");
            stmt.execute("CREATE TABLE player_roles (uuid TEXT PRIMARY KEY NOT NULL, role TEXT NOT NULL CHECK (role IN ('CITIZEN', 'POLICE', 'MEDIC', 'GOVERNMENT', 'BANKER')), assigned_by TEXT NOT NULL, assigned_at INTEGER NOT NULL, reason TEXT NOT NULL, FOREIGN KEY (uuid) REFERENCES player_profiles(uuid) ON DELETE CASCADE)");
            stmt.execute("CREATE TABLE role_history (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, previous_role TEXT NOT NULL, new_role TEXT NOT NULL, actor TEXT NOT NULL, reason TEXT NOT NULL, assigned_at INTEGER NOT NULL, FOREIGN KEY (player_uuid) REFERENCES player_profiles(uuid) ON DELETE CASCADE)");
            stmt.execute("INSERT INTO player_profiles VALUES ('" + uuid + "', 'LegacySalaryUser', 'LegacySalaryUser', '', '', 1000, 0)");
            stmt.execute("INSERT INTO finance_accounts VALUES ('" + uuid + "', 12345, 67890, 1000)");
            stmt.execute("INSERT INTO player_roles VALUES ('" + uuid + "', 'BANKER', 'Admin', 1000, 'legacy role')");
            stmt.execute("PRAGMA user_version = 5");
        }

        try (HrpProfileStorage migrated = new HrpProfileStorage(v5DbPath)) {
            assertEquals(12345L, migrated.loadFinanceAccount(uuid, 0, 1000).cashCents());
            assertEquals(67890L, migrated.loadFinanceAccount(uuid, 0, 1000).bankCents());
            assertEquals(HrpRole.BANKER, migrated.getPlayerRole(uuid));
            assertTrue(migrated.paySalary(uuid, HrpRole.BANKER, 100L, 60, 12L, 2000L).paid());
            assertEquals(12445L, migrated.loadFinanceAccount(uuid, 0, 2000).cashCents());
        }
    }
}
