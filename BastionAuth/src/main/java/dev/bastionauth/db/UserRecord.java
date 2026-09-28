package dev.bastionauth.db;

/** One registered account as stored in the {@code users} table. */
public record UserRecord(
        String uuid,
        String username,
        String phc,
        long createdAt,
        long lastLoginAt,     // 0 = never logged in
        String lastIpHmac,    // nullable
        String lastIpMasked,  // nullable
        int failedAttempts,
        long lockedUntil) {
}
