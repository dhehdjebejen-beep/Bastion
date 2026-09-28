package dev.bastionauth.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.bastionauth.crypto.AesGcm;
import dev.bastionauth.crypto.Secrets;
import dev.bastionauth.crypto.Totp;
import dev.bastionauth.db.Database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The second factor: TOTP seeds, backup codes and the setup handshake.
 *
 * <p>Enabling is two steps on purpose — generate, then prove the app has the
 * seed by typing one code — so nobody locks themselves out with a QR they
 * never scanned. Seeds are stored encrypted under the master key; backup
 * codes as keyed hashes, each good once. A code is never accepted for a
 * step at or below the last accepted one, so a shoulder-surfed code is
 * useless the moment it was used.
 *
 * <p>Worker-thread class: every method that touches the database is called
 * from the auth executor, never from the server thread.
 */
public final class TwoFactor {

    public enum Check { OK, WRONG, BACKUP_USED, NOT_ENABLED }

    public record Setup(byte[] secret, String base32, long createdAt) {}

    private static final Gson GSON = new Gson();
    private static final long SETUP_TTL_MS = 15 * 60_000L;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Database db;
    private final Secrets secrets;
    private final SecureRandom rng = new SecureRandom();
    private final int window;
    private final int backupCount;
    /** Accounts with a second factor — a fast answer for the login path. */
    private final Set<String> enabled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Setup> pending = new ConcurrentHashMap<>();

    public TwoFactor(Database db, Secrets secrets, int window, int backupCount) {
        this.db = db;
        this.secrets = secrets;
        this.window = Math.max(0, Math.min(window, 3));
        this.backupCount = Math.max(4, Math.min(backupCount, 16));
        this.enabled.addAll(db.totpUuids());
    }

    public boolean isEnabled(UUID uuid) {
        return enabled.contains(uuid.toString());
    }

    public int enabledCount() {
        return enabled.size();
    }

    // ------------------------------------------------------------------ setup

    /** A fresh seed for this account; replaces any unconfirmed one. */
    public Setup begin(UUID uuid) {
        byte[] secret = Totp.newSecret(rng);
        Setup s = new Setup(secret, Totp.base32(secret), System.currentTimeMillis());
        pending.put(uuid, s);
        return s;
    }

    public Setup pendingOf(UUID uuid) {
        Setup s = pending.get(uuid);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.createdAt() > SETUP_TTL_MS) {
            pending.remove(uuid);
            return null;
        }
        return s;
    }

    public void forgetPending(UUID uuid) {
        pending.remove(uuid);
    }

    /**
     * Confirms the pending seed with a live code and stores it. Returns the
     * backup codes in the clear — the only time they are ever readable.
     */
    public List<String> confirm(UUID uuid, String code) throws SQLException {
        Setup s = pendingOf(uuid);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        long step = Totp.verify(s.secret(), code, now, window, -1);
        if (step < 0) return null;
        List<String> codes = newBackupCodes();
        db.saveTotp(uuid.toString(), AesGcm.encrypt(secrets.totpKey(), s.secret()), now, step, GSON.toJson(hashAll(codes)));
        enabled.add(uuid.toString());
        pending.remove(uuid);
        return codes;
    }

    // ------------------------------------------------------------------ checking

    /** A login-time check: the TOTP code, or one of the backup codes. */
    public Check check(UUID uuid, String typed) {
        Database.TotpRow row = db.totpOf(uuid.toString());
        if (row == null) return Check.NOT_ENABLED;
        byte[] secret = AesGcm.decrypt(secrets.totpKey(), row.secretEnc());
        if (secret != null && Totp.normalizeCode(typed) != null) {
            long step = Totp.verify(secret, typed, System.currentTimeMillis(), window, row.lastStep());
            if (step >= 0) {
                db.updateTotpStep(uuid.toString(), step);
                return Check.OK;
            }
            return Check.WRONG;
        }
        // Not six digits: try the backup codes.
        String norm = normalizeBackup(typed);
        if (norm == null || row.backupJson() == null) return Check.WRONG;
        List<String> hashes = new ArrayList<>(GSON.fromJson(row.backupJson(), new TypeToken<List<String>>() {}.getType()));
        String h = hashBackup(norm);
        for (int i = 0; i < hashes.size(); i++) {
            if (MessageDigest.isEqual(hashes.get(i).getBytes(StandardCharsets.UTF_8), h.getBytes(StandardCharsets.UTF_8))) {
                hashes.remove(i);
                db.updateTotpBackup(uuid.toString(), GSON.toJson(hashes));
                return Check.BACKUP_USED;
            }
        }
        return Check.WRONG;
    }

    public int backupCodesLeft(UUID uuid) {
        Database.TotpRow row = db.totpOf(uuid.toString());
        if (row == null || row.backupJson() == null) return 0;
        List<String> hashes = GSON.fromJson(row.backupJson(), new TypeToken<List<String>>() {}.getType());
        return hashes == null ? 0 : hashes.size();
    }

    public long enabledAt(UUID uuid) {
        Database.TotpRow row = db.totpOf(uuid.toString());
        return row == null ? 0 : row.enabledAt();
    }

    /** New backup codes, the old ones void. */
    public List<String> regenerateBackup(UUID uuid) {
        if (!isEnabled(uuid)) return null;
        List<String> codes = newBackupCodes();
        db.updateTotpBackup(uuid.toString(), GSON.toJson(hashAll(codes)));
        return codes;
    }

    public boolean disable(UUID uuid) {
        boolean removed = db.deleteTotp(uuid.toString());
        enabled.remove(uuid.toString());
        pending.remove(uuid);
        return removed;
    }

    // ------------------------------------------------------------------ backup codes

    /** "XXXX-XXXX" from an alphabet without look-alikes. */
    public List<String> newBackupCodes() {
        List<String> out = new ArrayList<>(backupCount);
        for (int i = 0; i < backupCount; i++) {
            StringBuilder sb = new StringBuilder(9);
            for (int k = 0; k < 8; k++) {
                if (k == 4) sb.append('-');
                sb.append(CODE_ALPHABET.charAt(rng.nextInt(CODE_ALPHABET.length())));
            }
            out.add(sb.toString());
        }
        return Collections.unmodifiableList(out);
    }

    static String normalizeBackup(String typed) {
        if (typed == null) return null;
        String t = typed.replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        if (t.length() != 8) return null;
        for (int i = 0; i < 8; i++) if (CODE_ALPHABET.indexOf(t.charAt(i)) < 0) return null;
        return t;
    }

    private String hashBackup(String normalized) {
        return secrets.deviceHmac("bastionauth:backup:v1|" + normalized);
    }

    private List<String> hashAll(List<String> codes) {
        List<String> out = new ArrayList<>(codes.size());
        for (String c : codes) out.add(hashBackup(normalizeBackup(c)));
        return out;
    }
}
