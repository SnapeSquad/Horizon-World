package ru.hrp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** SQLite persistence for player profiles; all calls run on the profile storage executor. */
public final class HrpProfileStorage implements AutoCloseable {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String CREATE_PLAYER_PROFILES_SQL = """
            CREATE TABLE IF NOT EXISTS player_profiles (
                uuid TEXT PRIMARY KEY NOT NULL,
                player_name TEXT NOT NULL,
                first_join INTEGER NOT NULL CHECK (first_join >= 0),
                play_time_ticks INTEGER NOT NULL DEFAULT 0 CHECK (play_time_ticks >= 0)
            )
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO player_profiles (uuid, player_name, first_join, play_time_ticks)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = excluded.player_name,
                first_join = MIN(player_profiles.first_join, excluded.first_join),
                play_time_ticks = MAX(player_profiles.play_time_ticks, excluded.play_time_ticks)
            """;

    private final Connection connection;

    public record StoredProfile(UUID uuid, String name, long firstJoin, long playTimeTicks) {
        public StoredProfile {
            if (uuid == null) throw new IllegalArgumentException("uuid must not be null");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (firstJoin < 0) throw new IllegalArgumentException("firstJoin must not be negative");
            if (playTimeTicks < 0) throw new IllegalArgumentException("playTimeTicks must not be negative");
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
                INSERT INTO player_profiles (uuid, player_name, first_join, play_time_ticks)
                VALUES (?, ?, ?, 0)
                ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name
                """)) {
            insert.setString(1, uuid.toString());
            insert.setString(2, playerName);
            insert.setLong(3, nowMillis);
            insert.executeUpdate();
        }

        try (PreparedStatement select = connection.prepareStatement("""
                SELECT player_name, first_join, play_time_ticks
                FROM player_profiles
                WHERE uuid = ?
                """)) {
            select.setString(1, uuid.toString());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) throw new SQLException("Profile row missing after upsert for UUID " + uuid);
                return new StoredProfile(uuid, result.getString("player_name"),
                        result.getLong("first_join"), result.getLong("play_time_ticks"));
            }
        }
    }

    public void save(StoredProfile profile) throws SQLException {
        saveAll(List.of(profile));
    }

    /** Saves a consistent snapshot in one SQLite transaction. */
    public void saveAll(Collection<StoredProfile> profiles) throws SQLException {
        if (profiles.isEmpty()) return;

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement upsert = connection.prepareStatement(UPSERT_SQL)) {
            for (StoredProfile profile : profiles) {
                upsert.setString(1, profile.uuid().toString());
                upsert.setString(2, profile.name());
                upsert.setLong(3, profile.firstJoin());
                upsert.setLong(4, profile.playTimeTicks());
                upsert.addBatch();
            }
            upsert.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
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

    /**
     * Version 0 is the original unversioned database. CREATE TABLE IF NOT EXISTS
     * preserves the player_profiles table created by earlier HRPCore builds.
     */
    private void migrateV0ToV1() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_PLAYER_PROFILES_SQL);
            statement.execute("PRAGMA user_version = 1");
            connection.commit();
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
