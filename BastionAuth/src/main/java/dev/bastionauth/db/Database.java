package dev.bastionauth.db;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * SQLite persistence. WAL journaling with {@code synchronous=FULL} for crash
 * durability. All access goes through the single auth worker thread; methods
 * are additionally synchronized as a second line of defence. Every statement
 * is parameterised — no SQL is ever concatenated from user input.
 */
public final class Database implements AutoCloseable {
    private final Connection conn;

    public Database(Path file) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(5000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        this.conn = ds.getConnection();
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS users (
                      uuid TEXT PRIMARY KEY,
                      username TEXT NOT NULL,
                      username_lower TEXT NOT NULL UNIQUE,
                      phc TEXT NOT NULL,
                      created_at INTEGER NOT NULL,
                      reg_ip_hmac TEXT,
                      last_login_at INTEGER NOT NULL DEFAULT 0,
                      last_ip_hmac TEXT,
                      last_ip_masked TEXT,
                      failed_attempts INTEGER NOT NULL DEFAULT 0,
                      locked_until INTEGER NOT NULL DEFAULT 0
                    )""");
            // Twin-detection schema (v1.2): a deterministic, keyed password
            // fingerprint column. Nullable on purpose — accounts created
            // before this migration get theirs on the next successful login.
            migrateColumn("users", "pw_fp", "TEXT");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_reg_ip ON users(reg_ip_hmac)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_pw_fp ON users(pw_fp)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      at INTEGER NOT NULL,
                      event TEXT NOT NULL,
                      uuid TEXT,
                      username TEXT,
                      ip_masked TEXT,
                      detail TEXT
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_log(at)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS sessions (
                      uuid TEXT PRIMARY KEY,
                      ip_hmac TEXT NOT NULL,
                      expires_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS device_fingerprints (
                      uuid TEXT NOT NULL,
                      device_hash TEXT NOT NULL,
                      brand TEXT,
                      language TEXT,
                      view_distance INTEGER,
                      main_arm TEXT,
                      subnet TEXT,
                      first_seen INTEGER NOT NULL,
                      last_seen INTEGER NOT NULL,
                      PRIMARY KEY (uuid, device_hash)
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_devhash ON device_fingerprints(device_hash)");
            // v1.4 twin-detection material: install profile without the
            // network (survives Wi-Fi → mobile), the launcher-claimed UUID,
            // the address the client typed, and its declared plugin channels.
            migrateColumn("device_fingerprints", "profile_hash", "TEXT");
            migrateColumn("device_fingerprints", "client_uuid", "TEXT");
            migrateColumn("device_fingerprints", "host_used", "TEXT");
            migrateColumn("device_fingerprints", "channels_hash", "TEXT");
            migrateColumn("device_fingerprints", "channels", "TEXT");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_profhash ON device_fingerprints(profile_hash)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_clientuuid ON device_fingerprints(client_uuid)");
            // Permanent per-account link ledger: the best grade ever reached
            // for a pair, never pruned. The audit log keeps 90 days and the
            // anti-cheat's network tab keeps 90 days; this is the long memory.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS account_links (
                      pair_key TEXT PRIMARY KEY,
                      uuid_a TEXT NOT NULL,
                      uuid_b TEXT NOT NULL,
                      name_a TEXT,
                      name_b TEXT,
                      best_score INTEGER NOT NULL,
                      best_grade TEXT NOT NULL,
                      best_signals TEXT,
                      last_score INTEGER NOT NULL,
                      last_signals TEXT,
                      first_seen INTEGER NOT NULL,
                      last_seen INTEGER NOT NULL,
                      times INTEGER NOT NULL DEFAULT 1
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_links_a ON account_links(uuid_a)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_links_b ON account_links(uuid_b)");
            // Pre-registration acceptance of the user agreement (section 2).
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS agreement_acceptance (
                      uuid TEXT PRIMARY KEY,
                      agreement_version TEXT NOT NULL,
                      accepted_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS device_links (
                      hash_a TEXT NOT NULL,
                      hash_b TEXT NOT NULL,
                      link_count INTEGER NOT NULL DEFAULT 1,
                      first_seen INTEGER NOT NULL,
                      last_seen INTEGER NOT NULL,
                      PRIMARY KEY (hash_a, hash_b)
                    )""");
            // 1.6: second factor (seed encrypted under the master key) and the
            // codeword — a second Argon2 hash on the user row, nullable.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS totp (
                      uuid TEXT PRIMARY KEY,
                      secret_enc TEXT NOT NULL,
                      enabled_at INTEGER NOT NULL,
                      last_step INTEGER NOT NULL DEFAULT 0,
                      backup_json TEXT
                    )""");
            migrateColumn("users", "codeword_phc", "TEXT");
            // 1.8: the human verdict on a pair. Added as columns rather than a
            // side table so a link and the decision about it are one row and
            // can never drift apart. The default keeps every pre-existing link
            // at NEW, which is exactly the behaviour those links had before.
            migrateColumn("account_links", "verdict", "TEXT NOT NULL DEFAULT 'NEW'");
            migrateColumn("account_links", "verdict_by", "TEXT");
            migrateColumn("account_links", "verdict_at", "INTEGER NOT NULL DEFAULT 0");
            migrateColumn("account_links", "verdict_note", "TEXT");
        }
    }

    // ------------------------------------------------------------------
    // 1.6: second factor and codeword
    // ------------------------------------------------------------------

    public record TotpRow(String secretEnc, long enabledAt, long lastStep, String backupJson) {}

    public synchronized TotpRow totpOf(String uuid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT secret_enc, enabled_at, last_step, backup_json FROM totp WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new TotpRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4)) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public synchronized void saveTotp(String uuid, String secretEnc, long enabledAt, long lastStep, String backupJson) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO totp(uuid, secret_enc, enabled_at, last_step, backup_json) VALUES(?,?,?,?,?)
                ON CONFLICT(uuid) DO UPDATE SET secret_enc = excluded.secret_enc, enabled_at = excluded.enabled_at,
                  last_step = excluded.last_step, backup_json = excluded.backup_json""")) {
            ps.setString(1, uuid);
            ps.setString(2, secretEnc);
            ps.setLong(3, enabledAt);
            ps.setLong(4, lastStep);
            ps.setString(5, backupJson);
            ps.executeUpdate();
        }
    }

    public synchronized void updateTotpStep(String uuid, long lastStep) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE totp SET last_step = ? WHERE uuid = ?")) {
            ps.setLong(1, lastStep);
            ps.setString(2, uuid);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void updateTotpBackup(String uuid, String backupJson) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE totp SET backup_json = ? WHERE uuid = ?")) {
            ps.setString(1, backupJson);
            ps.setString(2, uuid);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized boolean deleteTotp(String uuid) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM totp WHERE uuid = ?")) {
            ps.setString(1, uuid);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Every account that has a second factor — for the in-memory set. */
    public synchronized java.util.Set<String> totpUuids() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM totp"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException ignored) {
        }
        return out;
    }

    public synchronized String codewordOf(String uuid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT codeword_phc FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public synchronized void updateCodeword(String uuid, String phcOrNull) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET codeword_phc = ? WHERE uuid = ?")) {
            ps.setString(1, phcOrNull);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public synchronized java.util.Set<String> codewordUuids() {
        java.util.Set<String> out = new java.util.HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM users WHERE codeword_phc IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException ignored) {
        }
        return out;
    }

    public record AuditRow(long at, String event, String ipMasked, String detail) {}

    /** The account's own trail, newest first — what {@code /auth история} shows the player. */
    public synchronized java.util.List<AuditRow> auditOf(String uuid, int limit) {
        java.util.List<AuditRow> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT at, event, ip_masked, detail FROM audit_log WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, uuid);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new AuditRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Resumable sessions (survive restarts; IP-bound + TTL-limited)
    // ------------------------------------------------------------------

    public record SessionRow(String uuid, String ipHmac, long expiresAt) {}

    /** Best-effort: session persistence must never break auth. */
    public synchronized void saveSession(String uuid, String ipHmac, long expiresAt) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO sessions(uuid, ip_hmac, expires_at) VALUES(?,?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, ipHmac);
            ps.setLong(3, expiresAt);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized java.util.List<SessionRow> loadSessions() {
        java.util.List<SessionRow> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT uuid, ip_hmac, expires_at FROM sessions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new SessionRow(rs.getString(1), rs.getString(2), rs.getLong(3)));
        } catch (SQLException ignored) {
        }
        return out;
    }

    public synchronized void pruneSessions(long now) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void deleteSession(String uuid) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void deleteAgreement(String uuid) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM agreement_acceptance WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void clearSessions() {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions")) {
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized int countUsers() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** username_lower → exact username, for the in-memory name cache. */
    public synchronized Map<String, String> allNames() throws SQLException {
        Map<String, String> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT username_lower, username FROM users");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) map.put(rs.getString(1), rs.getString(2));
        }
        return map;
    }

    private static final String USER_COLUMNS =
            "uuid, username, phc, created_at, last_login_at, last_ip_hmac, last_ip_masked, failed_attempts, locked_until";

    public synchronized UserRecord getByUuid(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + USER_COLUMNS + " FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readUser(rs) : null;
            }
        }
    }

    public synchronized UserRecord getByNameLower(String nameLower) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + USER_COLUMNS + " FROM users WHERE username_lower = ?")) {
            ps.setString(1, nameLower);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readUser(rs) : null;
            }
        }
    }

    public synchronized void insertUser(String uuid, String username, String usernameLower, String phc,
                                        long createdAt, String regIpHmac) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users(uuid, username, username_lower, phc, created_at, reg_ip_hmac) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.setString(3, usernameLower);
            ps.setString(4, phc);
            ps.setLong(5, createdAt);
            ps.setString(6, regIpHmac);
            ps.executeUpdate();
        }
    }

    public synchronized void updatePhc(String uuid, String phc) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET phc = ? WHERE uuid = ?")) {
            ps.setString(1, phc);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public synchronized void recordLoginSuccess(String uuid, long at, String ipHmac, String ipMasked) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET last_login_at = ?, last_ip_hmac = ?, last_ip_masked = ?, failed_attempts = 0, locked_until = 0 WHERE uuid = ?")) {
            ps.setLong(1, at);
            ps.setString(2, ipHmac);
            ps.setString(3, ipMasked);
            ps.setString(4, uuid);
            ps.executeUpdate();
        }
    }

    /** Increments the account's failure counter. @return the new counter value. */
    public synchronized int addFailure(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET failed_attempts = failed_attempts + 1 WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT failed_attempts FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized void lockAccount(String uuid, long untilMillis) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET locked_until = ?, failed_attempts = 0 WHERE uuid = ?")) {
            ps.setLong(1, untilMillis);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public synchronized void unlock(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET locked_until = 0, failed_attempts = 0 WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    public synchronized boolean deleteByNameLower(String nameLower) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE username_lower = ?")) {
            ps.setString(1, nameLower);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized int countByRegIp(String regIpHmac) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM users WHERE reg_ip_hmac = ?")) {
            ps.setString(1, regIpHmac);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // Device fingerprints & account links (anti-abuse)
    // ------------------------------------------------------------------

    /** Adds a nullable column if it does not exist yet (lightweight migration). */
    private void migrateColumn(String table, String column, String type) throws SQLException {
        try (PreparedStatement probe = conn.prepareStatement(
                "SELECT " + column + " FROM " + table + " LIMIT 1")) {
            probe.executeQuery();
            return; // column already present
        } catch (SQLException missing) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            }
        }
    }

    /** Link-analysis row: everything the casino-grade scorer needs about one account. */
    public record LinkRow(String uuid, String username, long createdAt,
                          String regIpHmac, String lastIpHmac, String pwFp) {}

    /** Link rows for a set of uuids (the device-sighting candidates). */
    public synchronized java.util.List<LinkRow> linkRowsForUuids(java.util.List<String> uuids) {
        java.util.List<LinkRow> out = new java.util.ArrayList<>();
        if (uuids == null || uuids.isEmpty()) return out;
        StringBuilder in = new StringBuilder("?");
        for (int i = 1; i < uuids.size(); i++) in.append(",?");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, username, created_at, reg_ip_hmac, last_ip_hmac, pw_fp "
                        + "FROM users WHERE uuid IN (" + in + ")")) {
            for (int i = 0; i < uuids.size(); i++) ps.setString(i + 1, uuids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new LinkRow(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** All accounts sharing the given password fingerprint (twin signal). */
    public synchronized java.util.List<LinkRow> rowsWithPwFp(String pwFp, String excludeUuid) {
        java.util.List<LinkRow> out = new java.util.ArrayList<>();
        if (pwFp == null) return out;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, username, created_at, reg_ip_hmac, last_ip_hmac, pw_fp "
                        + "FROM users WHERE pw_fp = ? AND uuid <> ?")) {
            ps.setString(1, pwFp);
            ps.setString(2, excludeUuid == null ? "" : excludeUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new LinkRow(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** Writes/refreshes the password fingerprint (registration, change, login). */
    public synchronized void updatePwFp(String uuid, String pwFp) {
        if (pwFp == null) return;
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET pw_fp = ? WHERE uuid = ?")) {
            ps.setString(1, pwFp);
            ps.setString(2, uuid);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /** The password fingerprint of one account, or null. */
    public synchronized String pwFpOf(String uuid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pw_fp FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    public record DeviceRow(String uuid, String deviceHash, String brand, String language,
                            int viewDistance, String mainArm, String subnet,
                            long firstSeen, long lastSeen) {}

    /** Inserts or refreshes a fingerprint sighting. */
    public synchronized void recordFingerprint(String uuid, String deviceHash, String brand,
                                                String language, int viewDistance, String mainArm,
                                                String subnet, long now) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO device_fingerprints(uuid, device_hash, brand, language, view_distance, main_arm, subnet, first_seen, last_seen)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(uuid, device_hash) DO UPDATE SET last_seen = excluded.last_seen""")) {
            ps.setString(1, uuid);
            ps.setString(2, deviceHash);
            ps.setString(3, brand);
            ps.setString(4, language);
            ps.setInt(5, viewDistance);
            ps.setString(6, mainArm);
            ps.setString(7, subnet);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /**
     * v1.4 material for a sighting, written right after {@link #recordFingerprint}
     * (same primary key). Nulls leave the existing value untouched, so the
     * channel list — which arrives seconds after the join — can be filled
     * in by a later call without erasing the pre-login fields.
     */
    public synchronized void updateFingerprintExtras(String uuid, String deviceHash, String profileHash,
                                                     String clientUuid, String hostUsed,
                                                     String channelsHash, String channels) {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE device_fingerprints SET
                  profile_hash = COALESCE(?, profile_hash),
                  client_uuid = COALESCE(?, client_uuid),
                  host_used = COALESCE(?, host_used),
                  channels_hash = COALESCE(?, channels_hash),
                  channels = COALESCE(?, channels)
                WHERE uuid = ? AND device_hash = ?""")) {
            ps.setString(1, profileHash);
            ps.setString(2, clientUuid);
            ps.setString(3, hostUsed);
            ps.setString(4, channelsHash);
            ps.setString(5, channels);
            ps.setString(6, uuid);
            ps.setString(7, deviceHash);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /** A sighting with the v1.4 columns — what the multi-signal linker matches on. */
    public record SightingRow(String uuid, String deviceHash, String profileHash, String clientUuid,
                              String hostUsed, String channelsHash, String channels, String subnet,
                              long firstSeen, long lastSeen) {}

    private static final String SIGHTING_COLUMNS =
            "uuid, device_hash, profile_hash, client_uuid, host_used, channels_hash, channels, subnet, first_seen, last_seen";

    private static SightingRow readSighting(ResultSet rs) throws SQLException {
        return new SightingRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getLong(10));
    }

    /** Sightings of other accounts matching one indexed column (device/profile/client_uuid/channels hash). */
    private java.util.List<SightingRow> sightingsWhere(String column, String value, String excludeUuid) {
        java.util.List<SightingRow> out = new java.util.ArrayList<>();
        if (value == null || value.isEmpty()) return out;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SIGHTING_COLUMNS + " FROM device_fingerprints WHERE " + column + " = ? AND uuid <> ?")) {
            ps.setString(1, value);
            ps.setString(2, excludeUuid == null ? "" : excludeUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readSighting(rs));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    public synchronized java.util.List<SightingRow> sightingsOfProfile(String profileHash, String excludeUuid) {
        return sightingsWhere("profile_hash", profileHash, excludeUuid);
    }

    public synchronized java.util.List<SightingRow> sightingsOfClientUuid(String clientUuid, String excludeUuid) {
        return sightingsWhere("client_uuid", clientUuid, excludeUuid);
    }

    public synchronized java.util.List<SightingRow> sightingsOfChannels(String channelsHash, String excludeUuid) {
        return sightingsWhere("channels_hash", channelsHash, excludeUuid);
    }

    /** All sightings of one account, newest first (admin view). */
    public synchronized java.util.List<SightingRow> sightingsOfUuid(String uuid) {
        java.util.List<SightingRow> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + SIGHTING_COLUMNS + " FROM device_fingerprints WHERE uuid = ? ORDER BY last_seen DESC")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readSighting(rs));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ permanent link ledger

    public record LinkLedgerRow(String uuidA, String uuidB, String nameA, String nameB,
                                int bestScore, String bestGrade, String bestSignals,
                                int lastScore, String lastSignals, long firstSeen, long lastSeen, int times,
                                String verdict, String verdictBy, long verdictAt, String verdictNote) {

        /** The other side of the pair, given one side's uuid. */
        public String otherUuid(String mine) {
            return uuidA.equals(mine) ? uuidB : uuidA;
        }

        /** The other side's last known name, given one side's uuid. */
        public String otherName(String mine) {
            String n = uuidA.equals(mine) ? nameB : nameA;
            return n == null || n.isBlank() ? "?" : n;
        }
    }

    /** The column list every link query selects, in the order {@link #readLink} expects. */
    private static final String LINK_COLUMNS =
            "uuid_a, uuid_b, name_a, name_b, best_score, best_grade, best_signals, last_score, last_signals, "
                    + "first_seen, last_seen, times, verdict, verdict_by, verdict_at, verdict_note";

    private static LinkLedgerRow readLink(ResultSet rs) throws SQLException {
        return new LinkLedgerRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9),
                rs.getLong(10), rs.getLong(11), rs.getInt(12),
                rs.getString(13), rs.getString(14), rs.getLong(15), rs.getString(16));
    }

    /** Order-independent key so A↔B and B↔A are one row. */
    public static String pairKey(String uuidA, String uuidB) {
        return uuidA.compareTo(uuidB) <= 0 ? uuidA + "|" + uuidB : uuidB + "|" + uuidA;
    }

    /**
     * Records one analysis result for a pair. The best score/grade ever seen
     * is kept forever; last_* tracks the most recent verdict so a pair that
     * used to look linked and stopped is still visible as such.
     */
    public synchronized void upsertLink(String uuidA, String nameA, String uuidB, String nameB,
                                        int score, String grade, String signals, long now) {
        String key = pairKey(uuidA, uuidB);
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO account_links(pair_key, uuid_a, uuid_b, name_a, name_b, best_score, best_grade, best_signals,
                                          last_score, last_signals, first_seen, last_seen, times)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,1)
                ON CONFLICT(pair_key) DO UPDATE SET
                  name_a = CASE WHEN account_links.uuid_a = excluded.uuid_a THEN excluded.name_a ELSE excluded.name_b END,
                  name_b = CASE WHEN account_links.uuid_b = excluded.uuid_b THEN excluded.name_b ELSE excluded.name_a END,
                  best_score = CASE WHEN excluded.best_score > account_links.best_score THEN excluded.best_score ELSE account_links.best_score END,
                  best_grade = CASE WHEN excluded.best_score > account_links.best_score THEN excluded.best_grade ELSE account_links.best_grade END,
                  best_signals = CASE WHEN excluded.best_score > account_links.best_score THEN excluded.best_signals ELSE account_links.best_signals END,
                  last_score = excluded.last_score,
                  last_signals = excluded.last_signals,
                  last_seen = excluded.last_seen,
                  times = account_links.times + 1""")) {
            ps.setString(1, key);
            ps.setString(2, uuidA);
            ps.setString(3, uuidB);
            ps.setString(4, nameA);
            ps.setString(5, nameB);
            ps.setInt(6, score);
            ps.setString(7, grade);
            ps.setString(8, signals);
            ps.setInt(9, score);
            ps.setString(10, signals);
            ps.setLong(11, now);
            ps.setLong(12, now);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /** Every pair the account has ever been part of, strongest first. */
    public synchronized java.util.List<LinkLedgerRow> linksOf(String uuid) {
        java.util.List<LinkLedgerRow> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + LINK_COLUMNS + " FROM account_links WHERE uuid_a = ? OR uuid_b = ? "
                        + "ORDER BY best_score DESC, last_seen DESC")) {
            ps.setString(1, uuid);
            ps.setString(2, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readLink(rs));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    /**
     * The whole ledger for the admin panel: unreviewed pairs first (they are
     * the ones a human still owes a decision), then by strength. Capped
     * because the panel paginates and nobody scrolls past a few hundred.
     */
    public synchronized java.util.List<LinkLedgerRow> allLinks(int limit) {
        java.util.List<LinkLedgerRow> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + LINK_COLUMNS + " FROM account_links "
                        + "ORDER BY CASE verdict WHEN 'NEW' THEN 0 ELSE 1 END, best_score DESC, last_seen DESC "
                        + "LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readLink(rs));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** One pair, or null when the ledger has never recorded it. */
    public synchronized LinkLedgerRow linkBetween(String uuidA, String uuidB) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + LINK_COLUMNS + " FROM account_links WHERE pair_key = ?")) {
            ps.setString(1, pairKey(uuidA, uuidB));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readLink(rs) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Files a human decision about a pair, creating the row when the analysis
     * never produced one. That second case is the whole point of the manual
     * path: staff can bind two accounts the score never reached 50 on (or,
     * far more often, pre-clear a pair before it ever trips a gate). A row
     * born this way carries score 0 and the signal {@code manual}, so the UI
     * never pretends the model found it.
     */
    public synchronized boolean setLinkVerdict(String uuidA, String uuidB, String verdict,
                                               String by, String note, long now) {
        String key = pairKey(uuidA, uuidB);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE account_links SET verdict = ?, verdict_by = ?, verdict_at = ?, verdict_note = ? "
                        + "WHERE pair_key = ?")) {
            ps.setString(1, verdict);
            ps.setString(2, by);
            ps.setLong(3, now);
            ps.setString(4, note);
            ps.setString(5, key);
            if (ps.executeUpdate() > 0) return true;
        } catch (SQLException e) {
            return false;
        }
        return false;
    }

    /** Creates a manual, score-less pair row carrying the given verdict. */
    public synchronized boolean declareLink(String uuidA, String nameA, String uuidB, String nameB,
                                            String verdict, String by, String note, long now) {
        String key = pairKey(uuidA, uuidB);
        // pairKey orders the two sides; the names must follow them, or the
        // row would show A's name against B's uuid.
        boolean flipped = !key.startsWith(uuidA);
        String lowUuid = flipped ? uuidB : uuidA;
        String lowName = flipped ? nameB : nameA;
        String highUuid = flipped ? uuidA : uuidB;
        String highName = flipped ? nameA : nameB;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO account_links(pair_key, uuid_a, uuid_b, name_a, name_b, best_score, best_grade,
                                          best_signals, last_score, last_signals, first_seen, last_seen, times,
                                          verdict, verdict_by, verdict_at, verdict_note)
                VALUES(?,?,?,?,?,0,'NONE','manual',0,'manual',?,?,0,?,?,?,?)
                ON CONFLICT(pair_key) DO UPDATE SET
                  verdict = excluded.verdict,
                  verdict_by = excluded.verdict_by,
                  verdict_at = excluded.verdict_at,
                  verdict_note = excluded.verdict_note""")) {
            ps.setString(1, key);
            ps.setString(2, lowUuid);
            ps.setString(3, highUuid);
            ps.setString(4, lowName);
            ps.setString(5, highName);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setString(8, verdict);
            ps.setString(9, by);
            ps.setLong(10, now);
            ps.setString(11, note);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Applies one verdict to every pair the account is part of. Returns how many rows changed. */
    public synchronized int setVerdictForAllLinksOf(String uuid, String verdict, String by, String note, long now) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE account_links SET verdict = ?, verdict_by = ?, verdict_at = ?, verdict_note = ? "
                        + "WHERE uuid_a = ? OR uuid_b = ?")) {
            ps.setString(1, verdict);
            ps.setString(2, by);
            ps.setLong(3, now);
            ps.setString(4, note);
            ps.setString(5, uuid);
            ps.setString(6, uuid);
            return ps.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    /** How many pairs still carry no human decision. */
    public synchronized int unreviewedLinkCount() {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM account_links WHERE verdict = 'NEW' AND best_score >= 80");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ agreement

    /** The agreement version this account last accepted, or null. */
    public synchronized String acceptedAgreement(String uuid) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT agreement_version FROM agreement_acceptance WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException ignored) {
        }
        return null;
    }

    /** Records (or replaces) this account's acceptance. */
    public synchronized void acceptAgreement(String uuid, String version, long at) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO agreement_acceptance(uuid, agreement_version, accepted_at) VALUES(?,?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, version);
            ps.setLong(3, at);
            ps.executeUpdate();
        } catch (SQLException e) {
            dev.bastionauth.BastionAuth.LOGGER.error("Could not store the agreement acceptance", e);
        }
    }

    /** How many accounts have accepted the given version. */
    public synchronized int countAgreementAccepts(String version) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM agreement_acceptance WHERE agreement_version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException ignored) {
        }
        return 0;
    }

    /** All sightings of the given device hash (excluding the named uuid). */
    public synchronized java.util.List<DeviceRow> sightingsOfDevice(String deviceHash, String excludeUuid) {
        java.util.List<DeviceRow> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, device_hash, brand, language, view_distance, main_arm, subnet, first_seen, last_seen "
                        + "FROM device_fingerprints WHERE device_hash = ? AND uuid <> ?")) {
            ps.setString(1, deviceHash);
            ps.setString(2, excludeUuid == null ? "" : excludeUuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new DeviceRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getLong(9)));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** Every hash the uuid has been seen with. */
    public synchronized java.util.List<String> hashesOfUuid(String uuid) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT device_hash FROM device_fingerprints WHERE uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** Names (from users) for the given uuids, for admin-facing link reports. */
    public synchronized java.util.Map<String, String> namesForUuids(java.util.List<String> uuids) {
        java.util.Map<String, String> out = new HashMap<>();
        if (uuids == null || uuids.isEmpty()) return out;
        StringBuilder in = new StringBuilder("?");
        for (int i = 1; i < uuids.size(); i++) in.append(",?");
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT uuid, username FROM users WHERE uuid IN (" + in + ")")) {
            for (int i = 0; i < uuids.size(); i++) ps.setString(i + 1, uuids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** Prunes fingerprint sightings older than the given horizon (TTL policy, section 8.4). */
    public synchronized void pruneFingerprints(long olderThanMillis) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM device_fingerprints WHERE last_seen < ?")) {
            ps.setLong(1, olderThanMillis);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /** Audit writes must never break the auth flow — errors are swallowed. */
    public synchronized void audit(long at, String event, String uuid, String username, String ipMasked, String detail) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_log(at, event, uuid, username, ip_masked, detail) VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, at);
            ps.setString(2, event);
            ps.setString(3, uuid);
            ps.setString(4, username);
            ps.setString(5, ipMasked);
            ps.setString(6, detail);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void pruneAudit(long olderThanMillis) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM audit_log WHERE at < ?")) {
            ps.setLong(1, olderThanMillis);
            ps.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    private static UserRecord readUser(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getString("uuid"),
                rs.getString("username"),
                rs.getString("phc"),
                rs.getLong("created_at"),
                rs.getLong("last_login_at"),
                rs.getString("last_ip_hmac"),
                rs.getString("last_ip_masked"),
                rs.getInt("failed_attempts"),
                rs.getLong("locked_until"));
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
