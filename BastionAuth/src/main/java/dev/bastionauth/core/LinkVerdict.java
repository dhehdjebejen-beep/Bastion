package dev.bastionauth.core;

import java.util.Locale;

/**
 * A human's standing decision about one account pair.
 *
 * <p>The correlation model answers "do these two look like one person"; it
 * cannot answer "and is that a problem here". Two brothers on one family PC,
 * a streamer and their alt, an owner testing payments between their own three
 * accounts — the signals are identical to a ban-evader's, and the model is
 * right to fire on all of them. What was missing was a way for staff to say
 * so <em>once</em>, rather than waving through the same pair every time it
 * touches money.
 *
 * <p>That is what a verdict is: a permanent, per-pair note on the link ledger
 * that every gate consults before it refuses anything. It does not delete the
 * link and does not weaken the analysis — the pair keeps accumulating score,
 * signals and sightings exactly as before, and staff can still see all of it.
 * It only changes what the server <em>does</em> about it.
 *
 * <ul>
 *   <li>{@link #NEW} — nobody has looked. The score decides, as it always did.</li>
 *   <li>{@link #TRUSTED} — reviewed and allowed. The pair may deal with each
 *       other as two strangers: transfers, duels, auction, trade, marriage,
 *       and one's overdue loan no longer bars the other from borrowing (the
 *       microloan limit itself ignores links since MaxCore 4.4.4). The referral bonus still
 *       counts them as one person — inviting yourself is not an invitation.
 *       The link stays on record.</li>
 *   <li>{@link #WATCH} — reviewed and kept restricted. Behaves exactly like
 *       {@code NEW}; the point is the reviewer's mark, so the pair stops
 *       showing up as unexamined in the queue.</li>
 *   <li>{@link #ALT} — declared a multi-account by a human. The gates fire
 *       regardless of score, which also makes it possible to bind a pair the
 *       analysis never scored high enough to catch.</li>
 * </ul>
 */
public enum LinkVerdict {
    NEW("Не проверена", "&e", "&7Решает оценка связи"),
    TRUSTED("Доверенная", "&a", "&7Без ограничений между ними; реферальный бонус — одно лицо"),
    WATCH("Под наблюдением", "&6", "&7Ограничения действуют, связь просмотрена"),
    ALT("Мультиаккаунт", "&c", "&7Ограничения действуют всегда, без оглядки на оценку");

    private final String label;
    private final String colour;
    private final String effect;

    LinkVerdict(String label, String colour, String effect) {
        this.label = label;
        this.colour = colour;
        this.effect = effect;
    }

    /** Russian name for the admin UI. */
    public String label() {
        return label;
    }

    /** Legacy colour code the label is normally painted in. */
    public String colour() {
        return colour;
    }

    /** One line describing what this verdict does to the gates. */
    public String effect() {
        return effect;
    }

    /** True when this verdict alone decides the gate, without consulting the score. */
    public boolean decidesAlone() {
        return this == TRUSTED || this == ALT;
    }

    /** What {@code accountsLinked} must answer when {@link #decidesAlone()}. */
    public boolean linkedWhenDecidingAlone() {
        return this == ALT;
    }

    /** Never throws: an unknown or absent value reads as {@link #NEW}. */
    public static LinkVerdict of(String raw) {
        if (raw == null) return NEW;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NEW;
        }
    }

    /** Accepts the Russian spellings staff actually type, plus the enum names. */
    public static LinkVerdict parse(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "доверять", "доверенная", "доверена", "своя", "trusted", "trust", "ok" -> TRUSTED;
            case "наблюдать", "наблюдение", "watch" -> WATCH;
            case "мульти", "мультиакк", "мультиаккаунт", "alt", "twin" -> ALT;
            case "сброс", "сбросить", "новая", "new", "reset", "-" -> NEW;
            default -> null;
        };
    }
}
