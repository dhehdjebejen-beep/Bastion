package dev.bastionac.core;

import dev.bastionac.BastionAC;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * The protocol a player's real client speaks, when ViaVersion translates for it.
 *
 * <p>Since the server takes 1.21.1 – 26.x clients through Via, a few checks must
 * know what the client actually sent. The one that matters: {@code ClientTickEnd}
 * exists from 1.21.2 (protocol 768). For an older client ViaBackwards makes one
 * up on a 50 ms timer of its own, so "the moves of one client tick" becomes
 * "the moves that happened to arrive within one timer window". On a jittery
 * connection two honest moves share a window — exactly what PacketOrder and the
 * NCP-step signature look for (a jump's +0.42 / +0.753 is that signature).
 *
 * <p>Reflection, not a dependency: without Via everyone is native.
 */
public final class ClientVersion {

    /** 1.21.2, the first protocol with a real ClientTickEnd. */
    public static final int FIRST_REAL_TICK_END = 768;

    private static volatile boolean resolved;
    private static volatile Object api;
    private static volatile Method getPlayerVersion;
    private static volatile Method getProtocolById;
    private static volatile Method protoIsKnown;
    private static volatile Method protoGetName;

    private ClientVersion() {}

    private static void resolve() {
        if (resolved) return;
        synchronized (ClientVersion.class) {
            if (resolved) return;
            try {
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                Object a = via.getMethod("getAPI").invoke(null);
                // From the public interface: the implementation class may not be public.
                Method m = Class.forName("com.viaversion.viaversion.api.ViaAPI").getMethod("getPlayerVersion", UUID.class);
                api = a;
                getPlayerVersion = m;
                try {
                    Class<?> pv = Class.forName("com.viaversion.viaversion.api.protocol.version.ProtocolVersion");
                    getProtocolById = pv.getMethod("getProtocol", int.class);
                    protoIsKnown = pv.getMethod("isKnown");
                    protoGetName = pv.getMethod("getName");
                } catch (Throwable ignored) {
                }
                BastionAC.LOGGER.info("ViaVersion found: client protocol versions are taken into account");
            } catch (ClassNotFoundException e) {
                // No Via: every client is native.
            } catch (Throwable t) {
                BastionAC.LOGGER.warn("ViaVersion present but its API could not be read ({}); treating every client as native",
                        t.toString());
            }
            resolved = true;
        }
    }

    /** The client's protocol number, or -1 when unknown (no Via, or not tracked yet). */
    public static int protocolOf(UUID player) {
        if (player == null) return -1;
        resolve();
        Method m = getPlayerVersion;
        if (m == null) return -1;
        try {
            Object v = m.invoke(api, player);
            return v instanceof Integer i ? i : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Human-readable Minecraft version string for a protocol number (ViaVersion + fallback table). */
    public static String versionLabel(int proto) {
        if (proto <= 0) {
            return viaPresent() ? "1.21.11 (нативная)" : "1.21.11";
        }
        resolve();
        Method gp = getProtocolById;
        Method ik = protoIsKnown;
        Method gn = protoGetName;
        if (gp != null && ik != null && gn != null) {
            try {
                Object pv = gp.invoke(null, proto);
                if (pv != null && Boolean.TRUE.equals(ik.invoke(pv))) {
                    Object name = gn.invoke(pv);
                    if (name instanceof String s && !s.isBlank() && !s.equalsIgnoreCase("UNKNOWN")) {
                        return s + " (proto " + proto + ")";
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        String mc = switch (proto) {
            case 777 -> "26.3";
            case 776 -> "26.2";
            case 775 -> "26.1";
            case 774 -> "1.21.11";
            case 773 -> "1.21.9–1.21.10";
            case 772 -> "1.21.7–1.21.8";
            case 771 -> "1.21.6";
            case 770 -> "1.21.5";
            case 769 -> "1.21.4";
            case 768 -> "1.21.2–1.21.3";
            case 767 -> "1.21–1.21.1";
            case 766 -> "1.20.5–1.20.6";
            case 765 -> "1.20.3–1.20.4";
            default -> null;
        };
        return mc != null ? mc + " (proto " + proto + ")" : "proto " + proto;
    }

    public static String versionLabel(UUID player) {
        return versionLabel(protocolOf(player));
    }

    /** ViaVersion is installed and its API answered. */
    public static boolean viaPresent() {
        resolve();
        return getPlayerVersion != null;
    }

    /** True when the client's tick ends are Via's timer, not the client's own. */
    public static boolean syntheticTickEnd(UUID player) {
        int p = protocolOf(player);
        return p > 0 && p < FIRST_REAL_TICK_END;
    }
}
