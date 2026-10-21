package dev.bastionac.core;

import dev.bastionac.BastionAC;
import dev.bastionac.config.ACConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proactive mode (3.5 beta): the checks only observe, Argus decides.
 *
 * <p>With the mode on, a check's flag still adds VL, alerts staff and opens
 * an Argus case, but its setback, cancel and kick are held back — unless the
 * check is one whose harm cannot wait for an analysis (flying, speed, timer,
 * impossible packets: {@link ACConfig.ProactiveCfg#alwaysMitigate}), or Argus
 * has already scored this player above {@link ACConfig.ProactiveCfg#enforceScore}.
 * From that score on, every check bites for that player for
 * {@link ACConfig.ProactiveCfg#enforceMinutes}. A confident guilty verdict
 * bans (Argus.proactiveVerdict). The VL ladder's own temp-bans, kicks and the
 * EDR chain ban stay silent: their evidence goes to Argus instead.
 *
 * <p>Hard physical caps that refuse an impossible action outright (a hit from
 * past 4.2 blocks, clicks over the CPS ceiling, a block clicked through a
 * wall) are not sanctions and stay as they are.
 *
 * <p>Without a working Argus the mode is off: nobody would ever decide.
 */
public final class Proactive {

    /** Player → until when (epoch ms) every check may mitigate for them. */
    private static final Map<UUID, Long> ENFORCED = new ConcurrentHashMap<>();

    private Proactive() {}

    /** Mode switched on and Argus there to decide. */
    public static boolean enabled() {
        ACConfig cfg = BastionAC.config();
        return cfg != null && cfg.proactive != null && cfg.proactive.enabled && dev.bastionac.argus.Argus.enabled();
    }

    /** Whether a flag of this check may set back / cancel / kick this player now. */
    public static boolean mayMitigate(UUID player, CheckType type) {
        if (!enabled()) return true;
        if (type != null && BastionAC.config().proactive.alwaysMitigate.contains(type.key)) return true;
        return isEnforced(player, System.currentTimeMillis());
    }

    public static boolean isEnforced(UUID player, long now) {
        Long until = ENFORCED.get(player);
        if (until == null) return false;
        if (until <= now) {
            ENFORCED.remove(player, until);
            return false;
        }
        return true;
    }

    /** Sanctions on for this player; returns true when they were not on already. */
    public static boolean enforce(UUID player, int minutes, long now) {
        Long before = ENFORCED.put(player, now + Math.max(1, minutes) * 60_000L);
        return before == null || before <= now;
    }

    /** Minutes of enforcement left (0 = none). */
    public static long minutesLeft(UUID player, long now) {
        Long until = ENFORCED.get(player);
        return until == null || until <= now ? 0 : (until - now + 59_999L) / 60_000L;
    }

    /** Staff cleared the player: sanctions off at once. */
    public static void clear(UUID player) {
        ENFORCED.remove(player);
    }

    public static int enforcedCount(long now) {
        ENFORCED.values().removeIf(u -> u <= now);
        return ENFORCED.size();
    }
}
