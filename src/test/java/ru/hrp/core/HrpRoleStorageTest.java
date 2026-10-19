package ru.hrp.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.hrp.core.HrpProfileStorage.RoleOperationResult;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HrpRoleStorageTest {

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
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void testInitialPlayerGetsDefaultCitizenRole() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        storage.loadOrCreate(playerUuid, "TestPlayer", System.currentTimeMillis());

        HrpRole role = storage.loadOrCreateRole(playerUuid, System.currentTimeMillis());
        assertEquals(HrpRole.CITIZEN, role);
    }

    @Test
    void testSetRoleAndAuditHistory() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        storage.loadOrCreate(playerUuid, "TestPlayer", System.currentTimeMillis());
        storage.loadOrCreateRole(playerUuid, System.currentTimeMillis());

        RoleOperationResult result = storage.setPlayerRole(
                playerUuid, HrpRole.MEDIC, "Admin1", "Promotion to medic", System.currentTimeMillis());

        assertTrue(result.success());
        assertEquals(HrpRole.CITIZEN, result.previousRole());
        assertEquals(HrpRole.MEDIC, result.newRole());

        assertEquals(HrpRole.MEDIC, storage.getPlayerRole(playerUuid));

        // Check history table directly via JDBC connection to temp file
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM role_history WHERE player_uuid = '" + playerUuid + "'")) {
            assertTrue(rs.next());
            assertEquals("CITIZEN", rs.getString("previous_role"));
            assertEquals("MEDIC", rs.getString("new_role"));
            assertEquals("Admin1", rs.getString("actor"));
            assertEquals("Promotion to medic", rs.getString("reason"));
        }
    }

    @Test
    void testReassigningSameRoleIsRejectedWithoutAuditLog() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        storage.loadOrCreate(playerUuid, "TestPlayer", System.currentTimeMillis());
        storage.loadOrCreateRole(playerUuid, System.currentTimeMillis());

        RoleOperationResult result = storage.setPlayerRole(
                playerUuid, HrpRole.CITIZEN, "Admin1", "Same role", System.currentTimeMillis());

        assertFalse(result.success());
        assertTrue(result.message().contains("уже имеет роль"));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM role_history WHERE player_uuid = '" + playerUuid + "'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void testMigrationV3ToV4AndV4ToV5Backfill() throws Exception {
        Path v3DbPath = tempDir.resolve("v3_profiles.db");

        // Create a V3 database manually
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + v3DbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE player_profiles (uuid TEXT PRIMARY KEY NOT NULL, player_name TEXT NOT NULL, rp_name TEXT NOT NULL, birth_date TEXT NOT NULL, biography TEXT NOT NULL, first_join INTEGER NOT NULL, play_time_ticks INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE finance_accounts (uuid TEXT PRIMARY KEY NOT NULL, cash_cents INTEGER NOT NULL DEFAULT 0, bank_cents INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE finance_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, transaction_id TEXT NOT NULL, player_uuid TEXT NOT NULL, type TEXT NOT NULL, amount_cents INTEGER NOT NULL, cash_delta_cents INTEGER NOT NULL, bank_delta_cents INTEGER NOT NULL, description TEXT NOT NULL, created_at INTEGER NOT NULL)");
            stmt.execute("INSERT INTO player_profiles VALUES ('" + UUID.randomUUID() + "', 'LegacyUser', 'LegacyUser', '', '', 1000, 0)");
            stmt.execute("PRAGMA user_version = 3");
        }

        // Open with HrpProfileStorage to trigger migration to V5
        try (HrpProfileStorage v3Storage = new HrpProfileStorage(v3DbPath)) {
            assertEquals(HrpRole.CITIZEN, v3Storage.findKnownPlayerByName("LegacyUser") != null ?
                    v3Storage.getPlayerRole(v3Storage.findKnownPlayerByName("LegacyUser").uuid()) : null);
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + v3DbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            ResultSet rsVersion = stmt.executeQuery("PRAGMA user_version");
            assertTrue(rsVersion.next());
            assertEquals(5, rsVersion.getInt(1));

            ResultSet rsRole = stmt.executeQuery("SELECT role, assigned_by, reason FROM player_roles");
            assertTrue(rsRole.next());
            assertEquals("CITIZEN", rsRole.getString("role"));
            assertEquals("SYSTEM", rsRole.getString("assigned_by"));
            assertEquals("Роль назначена по умолчанию при миграции схемы v4", rsRole.getString("reason"));

            ResultSet rsHistory = stmt.executeQuery("SELECT COUNT(*) FROM role_history");
            assertTrue(rsHistory.next());
            assertEquals(0, rsHistory.getInt(1));
        }
    }

    @Test
    void testMigrationV4ToV5EnforcesCheckConstraint() throws Exception {
        Path v4DbPath = tempDir.resolve("v4_profiles.db");

        UUID validUuid = UUID.randomUUID();
        UUID invalidUuid = UUID.randomUUID();

        // Create a V4 database manually without CHECK constraint and insert valid & invalid roles
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + v4DbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE player_profiles (uuid TEXT PRIMARY KEY NOT NULL, player_name TEXT NOT NULL, rp_name TEXT NOT NULL, birth_date TEXT NOT NULL, biography TEXT NOT NULL, first_join INTEGER NOT NULL, play_time_ticks INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE player_roles (uuid TEXT PRIMARY KEY NOT NULL, role TEXT NOT NULL, assigned_by TEXT NOT NULL, assigned_at INTEGER NOT NULL, reason TEXT NOT NULL)");
            stmt.execute("INSERT INTO player_profiles VALUES ('" + validUuid + "', 'ValidUser', 'ValidUser', '', '', 1000, 0)");
            stmt.execute("INSERT INTO player_profiles VALUES ('" + invalidUuid + "', 'InvalidUser', 'InvalidUser', '', '', 1000, 0)");
            stmt.execute("INSERT INTO player_roles VALUES ('" + validUuid + "', 'POLICE', 'Admin', 1000, 'Test')");
            stmt.execute("INSERT INTO player_roles VALUES ('" + invalidUuid + "', 'SUPERADMIN_INVALID', 'Admin', 1000, 'Test')");
            stmt.execute("PRAGMA user_version = 4");
        }

        // Migrate to V5
        try (HrpProfileStorage v4Storage = new HrpProfileStorage(v4DbPath)) {
            assertEquals(HrpRole.POLICE, v4Storage.getPlayerRole(validUuid));
            // Invalid role was converted to CITIZEN during migration
            assertEquals(HrpRole.CITIZEN, v4Storage.getPlayerRole(invalidUuid));

            // Verify that inserting an invalid role directly into V5 database throws SQLite Exception
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + v4DbPath.toAbsolutePath());
                 Statement stmt = conn.createStatement()) {
                assertThrows(SQLException.class, () ->
                        stmt.execute("INSERT INTO player_roles VALUES ('" + UUID.randomUUID() + "', 'SUPER_HERO', 'Admin', 1000, 'Test')"));
            }
        }
    }

    @Test
    void testGetPlayerRoleThrowsOnInvalidPersistedRole() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        storage.loadOrCreate(playerUuid, "TestUser", System.currentTimeMillis());

        // Bypassing CHECK constraint by disabling foreign keys / directly writing invalid role if possible or testing exception handling
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA ignore_check_constraints = ON");
            stmt.execute("INSERT OR REPLACE INTO player_roles VALUES ('" + playerUuid + "', 'INVALID_ROLE_STRING', 'SYSTEM', 1000, 'Corrupted')");
        }

        assertThrows(SQLException.class, () -> storage.getPlayerRole(playerUuid));
    }

    @Test
    void testClosedStorageThrowsOnRoleLoad() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        storage.close();

        assertThrows(SQLException.class, () -> storage.loadOrCreateRole(playerUuid, System.currentTimeMillis()));
        assertThrows(SQLException.class, () -> storage.getPlayerRole(playerUuid));
    }
}
