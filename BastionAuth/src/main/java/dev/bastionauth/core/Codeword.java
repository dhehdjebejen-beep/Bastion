package dev.bastionauth.core;

import dev.bastionauth.crypto.PasswordHasher;
import dev.bastionauth.db.Database;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кодовое слово: not a second factor, a second lock. A citizen who sets
 * one must speak it before the actions that would hurt most in the wrong
 * hands — changing the password, switching the second factor off, handing
 * a claim over, emptying the bank — and to take the word itself off.
 * Speaking it opens a short window; other mods ask {@link #protects} and
 * refuse while the window is closed.
 *
 * <p>Stored like a password (Argon2 with the pepper), verified on the auth
 * worker. Whether an account has one is kept in memory so the question
 * costs nothing on the server thread.
 */
public final class Codeword {

    private final Database db;
    private final PasswordHasher hasher;
    private final long unlockMillis;
    private final Set<String> hasWord = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> unlockedUntil = new ConcurrentHashMap<>();

    public Codeword(Database db, PasswordHasher hasher, int unlockMinutes) {
        this.db = db;
        this.hasher = hasher;
        this.unlockMillis = Math.max(1, unlockMinutes) * 60_000L;
        this.hasWord.addAll(db.codewordUuids());
    }

    public boolean isSet(UUID uuid) {
        return hasWord.contains(uuid.toString());
    }

    /** True while the word has been spoken recently. */
    public boolean isUnlocked(UUID uuid) {
        Long until = unlockedUntil.get(uuid);
        return until != null && until > System.currentTimeMillis();
    }

    /** The question other mods ask: a word is set and has not been spoken lately. */
    public boolean protects(UUID uuid) {
        return isSet(uuid) && !isUnlocked(uuid);
    }

    public long unlockedSecondsLeft(UUID uuid) {
        Long until = unlockedUntil.get(uuid);
        return until == null ? 0 : Math.max(0, (until - System.currentTimeMillis()) / 1000);
    }

    /** Worker thread. */
    public boolean verify(UUID uuid, String word) {
        String phc = db.codewordOf(uuid.toString());
        if (phc == null) return false;
        boolean ok = hasher.verify(phc, word) != PasswordHasher.VerifyResult.NO_MATCH;
        if (ok) unlockedUntil.put(uuid, System.currentTimeMillis() + unlockMillis);
        return ok;
    }

    /** Worker thread. */
    public void set(UUID uuid, String word) throws SQLException {
        db.updateCodeword(uuid.toString(), hasher.hash(word));
        hasWord.add(uuid.toString());
        unlockedUntil.put(uuid, System.currentTimeMillis() + unlockMillis);
    }

    /** Worker thread. */
    public void remove(UUID uuid) throws SQLException {
        db.updateCodeword(uuid.toString(), null);
        hasWord.remove(uuid.toString());
        unlockedUntil.remove(uuid);
    }

    public void lock(UUID uuid) {
        unlockedUntil.remove(uuid);
    }

    public void forget(UUID uuid) {
        unlockedUntil.remove(uuid);
    }

    /** Shape rules, the same spirit as the password policy but looser: a word, not a passphrase. */
    public static String shapeProblem(String word, int minLength, int maxLength, String accountName) {
        if (word == null || word.length() < minLength) return "codeword.tooShort";
        if (word.length() > maxLength) return "codeword.tooLong";
        if (word.equalsIgnoreCase(accountName)) return "codeword.equalsName";
        return null;
    }
}
