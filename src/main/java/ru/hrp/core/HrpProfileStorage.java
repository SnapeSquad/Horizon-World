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
    private static final int CURRENT_SCHEMA_VERSION = 2;

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
                FROM player_profiles
                WHERE uuid = ?
                """)) {
            select.setString(1, uuid.toString());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) throw new SQLException("Profile row missing after upsert for UUID " + uuid);
                String storedName = result.getString("player_name");
                String rpName = result.getString("rp_name");
                if (rpName == null || rpName.isBlank()) rpName = storedName;
                return new StoredProfile(
                        uuid,
                        storedName,
                        rpName,
                        result.getString("birth_date"),
                        result.getString("biography"),
                        result.getLong("first_join"),
                        result.getLong("play_time_ticks")
                );
            }
        }
    }

    /** Updates only character-editable RP fields; account UUID and session counters remain server-owned. */
    public void updateCharacterProfile(UUID uuid, String rpName, String birthDate, String biography)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE player_profiles
                SET rp_name = ?, birth_date = ?, biography = ?
                WHERE uuid = ?
                """)) {
            update.setString(1, rpName);
            update.setString(2, birthDate);
            update.setString(3, biography);
            update.setString(4, uuid.toString());
            int changed = update.executeUpdate();
            if (changed != 1) {
                throw new SQLException("Expected to update one profile row for UUID " + uuid + ", changed " + changed);
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
                case 1 -> migrateV1ToV2();
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

    /** Adds character RP fields. Existing profiles keep their first-join and play-time values. */
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
