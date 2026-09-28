package dev.bastionac.core;

import dev.bastionac.BastionAC;

import java.util.ArrayList;
import java.util.List;

/**
 * Reflection bridge to BastionAuth's link register.
 *
 * <p>The composite link analysis and its permanent ledger belong to
 * BastionAuth — it owns the device fingerprints, the IP HMACs and the password
 * fingerprints the correlation is built from, and it is the gate every other
 * mod asks "are these two one person". What lives here is only the admin's
 * view of it: staff already open the anti-cheat panel to look at a player, and
 * the link register is the same kind of question.
 *
 * <p>Resolved the same way every other cross-mod call in this codebase is:
 * by name, once, with every failure meaning "BastionAuth is absent or older".
 * An absent auth mod leaves the network tab showing its own subnet sightings
 * and nothing else — never an error, never a crash.
 */
public final class AuthLinks {

    /** Separator BastionAuth joins encoded row fields with. */
    private static final String SEP = "\u0001";
    /** Practical cap for the panel; the ledger itself is unbounded. */
    public static final int MAX_ROWS = 300;

    private static volatile boolean resolved;
    private static volatile java.lang.reflect.Method ledger;
    private static volatile java.lang.reflect.Method setVerdict;
    private static volatile java.lang.reflect.Method setAll;
    private static volatile java.lang.reflect.Method unreviewed;
    private static volatile java.lang.reflect.Method uuidOfName;

    private AuthLinks() {}

    /** One pair as the panel needs it. Everything is already a display string. */
    public record Link(String uuidA, String nameA, String uuidB, String nameB,
                       int bestScore, String bestGrade, String signals,
                       String verdict, String verdictBy, long verdictAt, String note,
                       long firstSeen, long lastSeen, int times) {

        /** True when no human has ruled on this pair yet. */
        public boolean unreviewed() {
            return verdict == null || verdict.isBlank() || "NEW".equals(verdict);
        }

        /** True when the pair exists only because staff declared it. */
        public boolean manual() {
            return "manual".equals(signals);
        }
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> cls = Class.forName("dev.bastionauth.BastionAuth");
            ledger = cls.getMethod("linkLedger", String.class, int.class);
            setVerdict = cls.getMethod("setLinkVerdict", String.class, String.class,
                    String.class, String.class, String.class);
            setAll = cls.getMethod("setVerdictForAllLinksOf", String.class, String.class,
                    String.class, String.class);
            unreviewed = cls.getMethod("unreviewedLinks");
            uuidOfName = cls.getMethod("uuidOfName", String.class);
        } catch (Throwable t) {
            ledger = null;
            setVerdict = null;
            setAll = null;
            unreviewed = null;
            uuidOfName = null;
        }
    }

    /** The registered uuid behind a name, or null when there is no such account. */
    public static String uuidOfName(String name) {
        resolve();
        if (uuidOfName == null || name == null || name.isBlank()) return null;
        try {
            Object id = uuidOfName.invoke(null, name);
            String s = id == null ? "" : String.valueOf(id);
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the register is reachable at all — the panel says so rather than showing an empty list. */
    public static boolean available() {
        resolve();
        return ledger != null;
    }

    /**
     * The register, unreviewed pairs first. Pass a uuid for one account's
     * pairs, or null for everything.
     */
    @SuppressWarnings("unchecked")
    public static List<Link> links(String uuidOrNull, int limit) {
        resolve();
        if (ledger == null) return List.of();
        List<Link> out = new ArrayList<>();
        try {
            Object raw = ledger.invoke(null, uuidOrNull, Math.min(Math.max(1, limit), MAX_ROWS));
            if (!(raw instanceof List<?> rows)) return List.of();
            for (Object row : (List<Object>) rows) {
                Link link = decode(String.valueOf(row));
                if (link != null) out.add(link);
            }
        } catch (Throwable t) {
            BastionAC.LOGGER.debug("Link register unavailable: {}", t.toString());
        }
        return out;
    }

    /** Fields, in the order BastionAuth encodes them. */
    static Link decode(String encoded) {
        String[] f = encoded.split(SEP, -1);
        if (f.length < 14) return null;
        try {
            return new Link(f[0], blankToQuestion(f[1]), f[2], blankToQuestion(f[3]),
                    Integer.parseInt(f[4]), f[5], f[6],
                    f[7].isEmpty() ? "NEW" : f[7], f[8], Long.parseLong(f[9]), f[10],
                    Long.parseLong(f[11]), Long.parseLong(f[12]), Integer.parseInt(f[13]));
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String blankToQuestion(String s) {
        return s == null || s.isBlank() ? "?" : s;
    }

    /** Files a decision about one pair. False when the register is absent or refused it. */
    public static boolean setVerdict(String uuidA, String uuidB, String verdict, String by, String note) {
        resolve();
        if (setVerdict == null) return false;
        try {
            return Boolean.TRUE.equals(setVerdict.invoke(null, uuidA, uuidB, verdict, by, note));
        } catch (Throwable t) {
            return false;
        }
    }

    /** One decision for every pair an account is part of. Returns rows changed. */
    public static int setVerdictForAll(String uuid, String verdict, String by, String note) {
        resolve();
        if (setAll == null) return 0;
        try {
            Object n = setAll.invoke(null, uuid, verdict, by, note);
            return n instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** How many strong pairs nobody has ruled on. */
    public static int unreviewedCount() {
        resolve();
        if (unreviewed == null) return 0;
        try {
            Object n = unreviewed.invoke(null);
            return n instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ display

    /** Russian label for a verdict id. Kept here so the panel never imports BastionAuth. */
    public static String verdictLabel(String verdict) {
        return switch (verdict == null ? "NEW" : verdict) {
            case "TRUSTED" -> "Доверенная";
            case "WATCH" -> "Под наблюдением";
            case "ALT" -> "Мультиаккаунт";
            default -> "Не проверена";
        };
    }

    /** Legacy colour code the verdict is painted in. */
    public static String verdictColour(String verdict) {
        return switch (verdict == null ? "NEW" : verdict) {
            case "TRUSTED" -> "&a";
            case "WATCH" -> "&6";
            case "ALT" -> "&c";
            default -> "&e";
        };
    }

    /** What the verdict does to the gates, in one line. */
    public static String verdictEffect(String verdict) {
        return switch (verdict == null ? "NEW" : verdict) {
            case "TRUSTED" -> "Без ограничений между ними; реферальный бонус — одно лицо";
            case "WATCH" -> "Ограничения действуют, связь просмотрена";
            case "ALT" -> "Ограничения действуют всегда, без оглядки на оценку";
            default -> "Решает оценка связи";
        };
    }

    /** Russian label for one analysis signal token. */
    public static String signalName(String token) {
        return switch (token) {
            case "device" -> "устройство";
            case "profile" -> "отпечаток клиента";
            case "launcher-uuid" -> "UUID лаунчера";
            case "mods" -> "набор модов";
            case "ip" -> "IP-адрес";
            case "host" -> "адрес входа";
            case "name" -> "похожий ник";
            case "timing" -> "время регистрации";
            case "password" -> "тот же пароль";
            case "manual" -> "заведена вручную";
            default -> token;
        };
    }
}
