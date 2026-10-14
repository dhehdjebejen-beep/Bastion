package dev.bastionauth.core;

/**
 * How many accounts one IP may register, and when the next one is allowed.
 *
 * <p>The first {@code base} accounts are free, as before. Past that, with the
 * extra quota on, the IP is not refused for good: the next account opens one
 * step (a week by default) after the latest registration from that IP, the
 * one after it two steps after that, then three, and so on. A household or a
 * carrier NAT can still grow over time; a farm of throwaway accounts cannot
 * be registered in an evening.
 *
 * <pre>
 *   accounts already on the IP:  base-1   base    base+1   base+2
 *   next one allowed:            now      +1 step +2 steps +3 steps   (after the latest registration)
 * </pre>
 */
public final class RegistrationQuota {

    /** Never: the extra quota is off or the hard cap is reached. */
    public static final long NEVER = Long.MAX_VALUE;

    private RegistrationQuota() {}

    /**
     * When a new registration from this IP becomes allowed (epoch ms); a value
     * at or before now means "now", {@link #NEVER} means not at all.
     *
     * @param existing    accounts already registered from the IP
     * @param lastRegAt   when the latest of them was registered (0 if none)
     * @param base        the free allowance ({@code maxAccountsPerIp})
     * @param extra       whether the waiting quota past {@code base} is on
     * @param stepMs      the waiting step
     * @param hardCap     absolute ceiling, 0 = none
     */
    public static long allowedAt(int existing, long lastRegAt, int base, boolean extra, long stepMs, int hardCap) {
        if (existing < base) return 0;
        if (!extra || stepMs <= 0) return NEVER;
        if (hardCap > 0 && existing >= hardCap) return NEVER;
        long k = (long) existing - base + 1;           // 1 for the first account past the base
        if (k > Long.MAX_VALUE / stepMs) return NEVER;
        return lastRegAt + k * stepMs;
    }
}
